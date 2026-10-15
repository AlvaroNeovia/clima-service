package br.com.neoviaengenharia.clima.app.observado;

import br.com.neoviaengenharia.clima.app.regiao.CatalogoRegioesService;
import br.com.neoviaengenharia.clima.app.regiao.FonteIndisponivelException;
import br.com.neoviaengenharia.clima.domain.observado.AplicacaoBusca;
import br.com.neoviaengenharia.clima.domain.observado.EstadoHora;
import br.com.neoviaengenharia.clima.domain.observado.HoraNimbus;
import br.com.neoviaengenharia.clima.domain.observado.JanelaObservada;
import br.com.neoviaengenharia.clima.domain.observado.PlanoBusca;
import br.com.neoviaengenharia.clima.domain.regiao.Regiao;
import br.com.neoviaengenharia.clima.infra.cache.ObservadoCache;
import br.com.neoviaengenharia.clima.infra.nimbus.NimbusClient;
import br.com.neoviaengenharia.clima.infra.nimbus.NimbusIndisponivelException;
import br.com.neoviaengenharia.clima.infra.nimbus.NimbusRegiaoRecusadaException;
import br.com.neoviaengenharia.clima.infra.repo.HoraObservada;
import br.com.neoviaengenharia.clima.infra.repo.HoraObservadaRepository;
import br.com.neoviaengenharia.clima.infra.repo.RegiaoRepository;
import br.com.neoviaengenharia.clima.infra.repo.TentativaObservado;
import br.com.neoviaengenharia.clima.infra.repo.TentativaObservadoRepository;
import br.com.neoviaengenharia.clima.infra.repo.TravaRegiaoRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Precipitação observada: Caffeine → Redis → SQL → Nimbus (S4-FR-01..17).
 *
 * <p>A busca na Nimbus acontece dentro da transação que trava a linha da região em {@code regiao}: as
 * requisições (de todas as instâncias) para a mesma região se enfileiram, e as seguintes encontram as horas
 * já buscadas ao reler o SQL.
 */
@Service
public class PrecipitacaoObservadaService {

    private static final Logger log = LoggerFactory.getLogger(PrecipitacaoObservadaService.class);

    private final CatalogoRegioesService catalogo;
    private final RegiaoRepository regioes;
    private final TravaRegiaoRepository trava;
    private final HoraObservadaRepository horas;
    private final TentativaObservadoRepository tentativas;
    private final ObservadoCache cache;
    private final NimbusClient nimbus;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PrecipitacaoObservadaService(
            CatalogoRegioesService catalogo,
            RegiaoRepository regioes,
            TravaRegiaoRepository trava,
            HoraObservadaRepository horas,
            TentativaObservadoRepository tentativas,
            ObservadoCache cache,
            NimbusClient nimbus,
            PlatformTransactionManager transacoes,
            Clock clock) {
        this.catalogo = catalogo;
        this.regioes = regioes;
        this.trava = trava;
        this.horas = horas;
        this.tentativas = tentativas;
        this.cache = cache;
        this.nimbus = nimbus;
        this.tx = new TransactionTemplate(transacoes);
        this.clock = clock;
    }

    /**
     * @throws RegiaoNaoEncontradaException se a região não existir em {@code regiao} ou a Nimbus a recusar
     * @throws FonteIndisponivelException se a Nimbus falhar e não houver hora com dado na janela
     */
    public ObservadoResultado consultar(long regionId, JanelaObservada janela) {
        Instant agora = clock.instant();
        List<Instant> horasJanela = janela.horasAte(agora);
        catalogo.obter(); // renova o catálogo vencido (S1-FR-08) antes de ler a situação da região
        Regiao regiao = regioes.findById(regionId).orElseThrow(RegiaoNaoEncontradaException::new);
        if (horasJanela.isEmpty()) {
            return resultado(regionId, janela, false, Map.of());
        }

        Map<Instant, EstadoHora> estados = lerComCache(regionId, horasJanela);
        if (regiao.isInativa() || regiao.isRemovida()
                || horasJanela.stream().noneMatch(h -> estados.get(h).deveBuscar(agora))) {
            return resultado(regionId, janela, false, estados);
        }

        Busca busca;
        try {
            busca = tx.execute(s -> buscarComTrava(regionId, horasJanela, agora));
        } catch (NimbusRegiaoRecusadaException e) {
            log.warn("Nimbus recusou a região {}; respondendo como região não encontrada", regionId);
            throw new RegiaoNaoEncontradaException();
        } catch (DataAccessException | TransactionException e) {
            // DataAccessException inteira, como no CatalogoRegioesService: com o timeout da trava o pool pode
            // descartar a conexão e o erro chegar como JpaSystemException. Serve o que foi lido antes da trava.
            log.warn("Não foi possível obter a trava ou gravar o observado (regiao={}, erro={}); servindo o gravado",
                    regionId, e.getClass().getSimpleName());
            busca = new Busca(false, estados, Map.of());
        }

        if (busca.ok()) {
            cache.guardar(regionId, busca.alterados().values());
            return resultado(regionId, janela, false, busca.estados());
        }
        if (busca.estados().values().stream().noneMatch(EstadoHora::temDado)) {
            throw new FonteIndisponivelException();
        }
        log.warn("Observado servido desatualizado (regiao={})", regionId);
        return resultado(regionId, janela, true, busca.estados());
    }

    /** Roda com a região travada: relê o SQL, busca na Nimbus as horas que ainda precisam e grava. */
    private Busca buscarComTrava(long regionId, List<Instant> horasJanela, Instant agora) {
        trava.travar(regionId);
        Instant de = horasJanela.get(0);
        Instant ate = horasJanela.get(horasJanela.size() - 1).plus(Duration.ofHours(1));
        Map<Instant, HoraObservada> gravadas = porHora(
                horas.findAllByRegionIdAndDataHoraGreaterThanEqualAndDataHoraLessThan(regionId, de, ate),
                HoraObservada::getDataHora);
        Map<Instant, TentativaObservado> tentadas = porHora(
                tentativas.findAllByRegionIdAndDataHoraGreaterThanEqualAndDataHoraLessThan(regionId, de, ate),
                TentativaObservado::getDataHora);
        Map<Instant, EstadoHora> estados = new HashMap<>();
        horasJanela.forEach(h -> estados.put(h, estado(h, gravadas.get(h), tentadas.get(h))));

        Set<Instant> aBuscar = horasJanela.stream().filter(h -> estados.get(h).deveBuscar(agora))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (aBuscar.isEmpty()) {
            return new Busca(true, estados, Map.of()); // outra requisição/instância acabou de buscar
        }

        PlanoBusca plano = PlanoBusca.de(aBuscar);
        List<HoraNimbus> recebidas;
        try {
            recebidas = nimbus.buscarObservado(regionId, plano.inicio(), plano.fimExclusivo());
        } catch (NimbusIndisponivelException e) {
            log.warn("Busca do observado na Nimbus falhou (regiao={}, motivo={}); servindo o gravado, se houver",
                    regionId, e.motivo());
            return new Busca(false, estados, Map.of()); // nada gravado: a falha não conta como tentativa
        }

        Map<Instant, EstadoHora> alterados = AplicacaoBusca.aplicar(estados, aBuscar, recebidas, agora);
        alterados.forEach((h, e) -> {
            HoraObservada gravada = gravadas.get(h);
            if (gravada != null) {
                gravada.atualizarCom(e);
            } else if (e.temDado() || e.semDado()) {
                horas.save(HoraObservada.de(regionId, e));
            }
            TentativaObservado t = tentadas.get(h);
            if (t != null) {
                t.registrar(agora);
            } else {
                tentativas.save(new TentativaObservado(regionId, h, agora));
            }
        });
        estados.putAll(alterados);
        return new Busca(true, estados, alterados);
    }

    /** Estados das horas: L1/L2 primeiro; as faltas vêm do SQL e preenchem o cache (S4-FR-14). */
    private Map<Instant, EstadoHora> lerComCache(long regionId, List<Instant> horasJanela) {
        Map<Instant, EstadoHora> estados = new HashMap<>(cache.obter(regionId, horasJanela));
        List<Instant> faltas = horasJanela.stream().filter(h -> !estados.containsKey(h)).toList();
        if (faltas.isEmpty()) {
            return estados;
        }
        Instant de = faltas.get(0);
        Instant ate = faltas.get(faltas.size() - 1).plus(Duration.ofHours(1));
        Map<Instant, HoraObservada> gravadas = porHora(
                horas.findAllByRegionIdAndDataHoraGreaterThanEqualAndDataHoraLessThan(regionId, de, ate),
                HoraObservada::getDataHora);
        Map<Instant, TentativaObservado> tentadas = porHora(
                tentativas.findAllByRegionIdAndDataHoraGreaterThanEqualAndDataHoraLessThan(regionId, de, ate),
                TentativaObservado::getDataHora);
        List<EstadoHora> lidos = faltas.stream().map(h -> estado(h, gravadas.get(h), tentadas.get(h))).toList();
        lidos.forEach(e -> estados.put(e.dataHora(), e));
        cache.guardar(regionId, lidos);
        return estados;
    }

    private static EstadoHora estado(Instant h, HoraObservada gravada, TentativaObservado tentativa) {
        Instant ultima = tentativa == null ? null : tentativa.getUltimaTentativaEm();
        return gravada != null ? gravada.estado(ultima) : new EstadoHora(h, null, null, false, ultima);
    }

    private static <T> Map<Instant, T> porHora(List<T> itens, Function<T, Instant> hora) {
        return itens.stream().collect(Collectors.toMap(hora, Function.identity()));
    }

    private static ObservadoResultado resultado(
            long regionId, JanelaObservada janela, boolean desatualizado, Map<Instant, EstadoHora> estados) {
        List<EstadoHora> comDado = estados.values().stream()
                .filter(EstadoHora::temDado)
                .sorted(Comparator.comparing(EstadoHora::dataHora))
                .toList();
        return new ObservadoResultado(regionId, janela, desatualizado, comDado);
    }

    /** Resultado da etapa travada; {@code ok=false} quando a Nimbus falhou (ou a trava esgotou). */
    private record Busca(boolean ok, Map<Instant, EstadoHora> estados, Map<Instant, EstadoHora> alterados) {}
}

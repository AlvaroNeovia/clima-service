package br.com.neoviaengenharia.clima.app.previsao;

import br.com.neoviaengenharia.clima.app.regiao.MotivoRecusa;
import br.com.neoviaengenharia.clima.app.regiao.ValidacaoRegiao;
import br.com.neoviaengenharia.clima.domain.previsao.ComparacaoRodada;
import br.com.neoviaengenharia.clima.domain.previsao.Decisao;
import br.com.neoviaengenharia.clima.domain.previsao.FiltroJanela;
import br.com.neoviaengenharia.clima.domain.previsao.PontoPrevisao;
import br.com.neoviaengenharia.clima.domain.previsao.PrevisaoVigente;
import br.com.neoviaengenharia.clima.domain.previsao.RegraAtualizacao;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaPrevisao;
import br.com.neoviaengenharia.clima.infra.cache.PrevisaoCache;
import br.com.neoviaengenharia.clima.infra.nimbus.NimbusClient;
import br.com.neoviaengenharia.clima.infra.nimbus.NimbusIndisponivelException;
import br.com.neoviaengenharia.clima.infra.repo.RodadaPrevisaoRepositorio;
import br.com.neoviaengenharia.clima.infra.repo.TentativaPrevisao;
import br.com.neoviaengenharia.clima.infra.repo.TentativaPrevisaoRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Previsão vigente por região: Caffeine → Redis → SQL → Nimbus (S2-FR-01..17).
 *
 * <p>As regiões que precisam de dado novo são buscadas numa única chamada, dentro de uma transação que trava as
 * linhas de {@code tentativa_previsao} em ordem de regionId: pedidos concorrentes (em qualquer instância) pelas
 * mesmas regiões se enfileiram e, ao obter a trava, encontram a tentativa recém-registrada e não chamam de novo.
 */
@Service
public class PrevisaoVigenteService {

    private static final Logger log = LoggerFactory.getLogger(PrevisaoVigenteService.class);

    private final ValidacaoRegiao validacao;
    private final PrevisaoCache cache;
    private final RodadaPrevisaoRepositorio rodadas;
    private final TentativaPrevisaoRepository tentativas;
    private final NimbusClient nimbus;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PrevisaoVigenteService(
            ValidacaoRegiao validacao,
            PrevisaoCache cache,
            RodadaPrevisaoRepositorio rodadas,
            TentativaPrevisaoRepository tentativas,
            NimbusClient nimbus,
            PlatformTransactionManager transacoes,
            Clock clock) {
        this.validacao = validacao;
        this.cache = cache;
        this.rodadas = rodadas;
        this.tentativas = tentativas;
        this.nimbus = nimbus;
        this.tx = new TransactionTemplate(transacoes);
        this.clock = clock;
    }

    /**
     * @param regionIds de 1 a 20 ids distintos, na ordem da resposta
     * @param inicio limite inferior inclusivo dos pontos, ou {@code null}
     * @param fim limite superior inclusivo dos pontos, ou {@code null}
     */
    public ConsultaPrevisao consultar(List<Long> regionIds, Instant inicio, Instant fim) {
        Instant agora = clock.instant();
        Map<Long, ItemPrevisao> itens = new HashMap<>();
        List<Long> pendentes = new ArrayList<>();
        for (long id : regionIds) {
            Optional<MotivoRecusa> recusa = validacao.verificar(id);
            if (recusa.isPresent()) {
                itens.put(id, ItemPrevisao.erro(id, recusa.get() == MotivoRecusa.INATIVA
                        ? CodigoErroRegiao.INATIVA : CodigoErroRegiao.NAO_ENCONTRADA));
                continue;
            }
            Optional<PrevisaoVigente> emCache = cache.obter(id);
            if (emCache.isPresent()) {
                itens.put(id, ItemPrevisao.com(emCache.get().rodada(), emCache.get().desatualizado()));
                continue;
            }
            Optional<RodadaPrevisao> gravada = rodadas.vigente(id);
            Optional<TentativaPrevisao> tentativa = tentativas.findById(id);
            Decisao d = decidir(gravada, tentativa.map(TentativaPrevisao::getUltimaTentativaEm),
                    tentativa.map(TentativaPrevisao::isUltimaFalhou).orElse(false), agora);
            if (d.buscar()) {
                pendentes.add(id);
            } else {
                itens.put(id, resolver(id, gravada, d, true));
            }
        }
        if (!pendentes.isEmpty()) {
            itens.putAll(buscar(pendentes, agora));
        }
        return new ConsultaPrevisao(regionIds.stream().map(id -> filtrar(itens.get(id), inicio, fim)).toList());
    }

    private Map<Long, ItemPrevisao> buscar(List<Long> pendentes, Instant agora) {
        List<Long> ordenados = pendentes.stream().sorted().toList();
        ordenados.forEach(tentativas::garantir);
        Busca busca;
        try {
            busca = tx.execute(s -> buscarComTrava(ordenados, agora));
        } catch (DataAccessException | TransactionException e) {
            // Tipicamente a espera pela trava esgotada (outra instância presa numa Nimbus lenta): trata como falha
            // da fonte, sem registrar tentativa nem guardar no cache.
            log.warn("Não foi possível obter a trava da previsão ou concluir a busca ({}), regioes={}",
                    e.getClass().getSimpleName(), ordenados);
            Map<Long, ItemPrevisao> itens = new HashMap<>();
            for (long id : ordenados) {
                itens.put(id, resolver(id, rodadas.vigente(id), Decisao.usarGravada(agora, true), false));
            }
            return itens;
        }
        busca.sobrescritas().forEach(r -> cache.invalidarRodada(r.regionId(), r.rodada()));
        busca.resolvidas().forEach((id, r) -> r.paraCache().ifPresent(cache::guardar));
        Map<Long, ItemPrevisao> itens = new HashMap<>();
        busca.resolvidas().forEach((id, r) -> itens.put(id, r.item()));
        return itens;
    }

    /** Roda com as linhas de tentativa travadas: decide de novo, registra a tentativa e chama a Nimbus uma vez. */
    private Busca buscarComTrava(List<Long> ids, Instant agora) {
        Map<Long, Resolucao> resolvidas = new LinkedHashMap<>();
        List<RodadaPrevisao> sobrescritas = new ArrayList<>();
        Map<Long, TentativaPrevisao> paraBuscar = new LinkedHashMap<>();
        Map<Long, Optional<RodadaPrevisao>> gravadas = new HashMap<>();
        for (TentativaPrevisao t : tentativas.travar(ids)) {
            long id = t.getRegionId();
            Optional<RodadaPrevisao> gravada = rodadas.vigente(id);
            Decisao d = decidir(gravada, Optional.ofNullable(t.getUltimaTentativaEm()), t.isUltimaFalhou(), agora);
            if (d.buscar()) {
                paraBuscar.put(id, t);
                gravadas.put(id, gravada);
            } else {
                resolvidas.put(id, resolucao(id, gravada, d)); // outra requisição/instância acabou de buscar
            }
        }
        if (paraBuscar.isEmpty()) {
            return new Busca(resolvidas, sobrescritas);
        }

        Map<Long, List<PontoPrevisao>> recebidas;
        try {
            recebidas = nimbus.buscarPrevisoes(new ArrayList<>(paraBuscar.keySet()),
                    RegraAtualizacao.inicioJanela(agora), RegraAtualizacao.fimJanela(agora));
        } catch (NimbusIndisponivelException e) {
            log.warn("Busca de previsões na Nimbus falhou (motivo={}), regioes={}", e.motivo(), paraBuscar.keySet());
            recebidas = Map.of();
        }

        Map<Long, String> resumo = new LinkedHashMap<>();
        for (Map.Entry<Long, TentativaPrevisao> e : paraBuscar.entrySet()) {
            long id = e.getKey();
            List<PontoPrevisao> pontos = recebidas.get(id);
            Optional<RodadaPrevisao> vigente = gravadas.get(id);
            boolean falhou = pontos == null;
            if (falhou) {
                resumo.put(id, "falha");
            } else if (pontos.isEmpty()) {
                resumo.put(id, "vazio");
            } else {
                Instant recebida = RodadaPrevisao.rodadaDe(pontos);
                ComparacaoRodada c = ComparacaoRodada.de(vigente.map(RodadaPrevisao::rodada), recebida);
                resumo.put(id, recebida + (c == ComparacaoRodada.ANTIGA ? " (antiga, ignorada)" : ""));
                if (c != ComparacaoRodada.ANTIGA) {
                    RodadaPrevisao nova = new RodadaPrevisao(id, recebida, agora, pontos);
                    rodadas.gravar(nova);
                    vigente = Optional.of(nova);
                    if (c == ComparacaoRodada.MESMA) {
                        sobrescritas.add(nova);
                    }
                }
            }
            e.getValue().registrar(agora, falhou);
            resolvidas.put(id, resolucao(id, vigente, decidir(vigente, Optional.of(agora), falhou, agora)));
        }
        log.info("previsão: rodadas recebidas {}", resumo);
        return new Busca(resolvidas, sobrescritas);
    }

    private static Decisao decidir(
            Optional<RodadaPrevisao> gravada, Optional<Instant> ultimaTentativa, boolean ultimaFalhou, Instant agora) {
        return RegraAtualizacao.decidir(gravada.map(RodadaPrevisao::rodada), ultimaTentativa, ultimaFalhou, agora);
    }

    /** Item a partir da rodada gravada e da decisão; guarda no cache, se pedido e houver rodada. */
    private ItemPrevisao resolver(long id, Optional<RodadaPrevisao> gravada, Decisao d, boolean guardar) {
        Resolucao r = resolucao(id, gravada, d);
        if (guardar) {
            r.paraCache().ifPresent(cache::guardar);
        }
        return r.item();
    }

    private static Resolucao resolucao(long id, Optional<RodadaPrevisao> gravada, Decisao d) {
        if (gravada.isEmpty()) {
            return new Resolucao(d.desatualizado()
                    ? ItemPrevisao.erro(id, CodigoErroRegiao.FONTE_INDISPONIVEL)
                    : ItemPrevisao.semDado(id), Optional.empty());
        }
        return new Resolucao(ItemPrevisao.com(gravada.get(), d.desatualizado()),
                Optional.of(new PrevisaoVigente(gravada.get(), d.validoAte(), d.desatualizado())));
    }

    private static ItemPrevisao filtrar(ItemPrevisao item, Instant inicio, Instant fim) {
        if (item.rodada() == null || (inicio == null && fim == null)) {
            return item;
        }
        RodadaPrevisao r = item.rodada();
        return new ItemPrevisao(item.regionId(), r.comPontos(FiltroJanela.aplicar(r.pontos(), inicio, fim)),
                item.desatualizado(), item.erro());
    }

    private record Resolucao(ItemPrevisao item, Optional<PrevisaoVigente> paraCache) {}

    private record Busca(Map<Long, Resolucao> resolvidas, List<RodadaPrevisao> sobrescritas) {}
}

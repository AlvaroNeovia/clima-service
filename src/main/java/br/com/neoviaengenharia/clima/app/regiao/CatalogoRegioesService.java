package br.com.neoviaengenharia.clima.app.regiao;

import br.com.neoviaengenharia.clima.domain.regiao.CatalogoSnapshot;
import br.com.neoviaengenharia.clima.domain.regiao.ReconciliacaoCatalogo;
import br.com.neoviaengenharia.clima.domain.regiao.Regiao;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoNimbus;
import br.com.neoviaengenharia.clima.infra.cache.CatalogoCache;
import br.com.neoviaengenharia.clima.infra.nimbus.NimbusClient;
import br.com.neoviaengenharia.clima.infra.nimbus.NimbusIndisponivelException;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogo;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogoRepository;
import br.com.neoviaengenharia.clima.infra.repo.ControleCatalogo;
import br.com.neoviaengenharia.clima.infra.repo.ControleCatalogoRepository;
import br.com.neoviaengenharia.clima.infra.repo.RegiaoRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Catálogo de regiões: Caffeine → Redis → SQL → Nimbus (S1-FR-02..10, 14, 15).
 *
 * <p>A busca na Nimbus acontece dentro da transação que trava a linha de controle, de modo que
 * todas as instâncias se enfileiram e só a primeira chama a Nimbus.
 */
@Service
public class CatalogoRegioesService {

    static final Duration INTERVALO_APOS_FALHA = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(CatalogoRegioesService.class);

    private final CatalogoCache cache;
    private final RegiaoRepository regioes;
    private final BuscaCatalogoRepository buscas;
    private final ControleCatalogoRepository controle;
    private final NimbusClient nimbus;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CatalogoRegioesService(
            CatalogoCache cache,
            RegiaoRepository regioes,
            BuscaCatalogoRepository buscas,
            ControleCatalogoRepository controle,
            NimbusClient nimbus,
            PlatformTransactionManager transacoes,
            Clock clock) {
        this.cache = cache;
        this.regioes = regioes;
        this.buscas = buscas;
        this.controle = controle;
        this.nimbus = nimbus;
        this.tx = new TransactionTemplate(transacoes);
        this.clock = clock;
    }

    /** Catálogo pelas camadas de cache; só vai à Nimbus se o gravado tiver vencido (ou não existir). */
    public CatalogoResultado obter() {
        Optional<CatalogoSnapshot> emCache = cache.obter();
        if (emCache.isPresent()) {
            return new CatalogoResultado(emCache.get(), false);
        }
        return atualizar();
    }

    /** Como {@link #obter()}, mas ignora o L1/L2: lê o SQL e, se vencido, busca na Nimbus. */
    public CatalogoResultado atualizar() {
        Optional<CatalogoSnapshot> gravado = snapshotDoSql();
        Instant agora = clock.instant();
        if (gravado.isPresent() && gravado.get().vigente(agora)) {
            cache.guardar(gravado.get());
            return new CatalogoResultado(gravado.get(), false);
        }
        // leitura sem trava e sem anexar a entidade: durante a pane ninguém espera pela linha de controle
        if (dentroDoIntervaloAposFalha(controle.ultimaFalhaEm().orElse(null), agora)) {
            return fallback();
        }
        AtomicBoolean nimbusRespondeu = new AtomicBoolean(false);
        Optional<CatalogoSnapshot> atual;
        try {
            atual = tx.execute(s -> buscarComTrava(nimbusRespondeu));
        } catch (DataAccessException | TransactionException e) {
            // Pega DataAccessException inteira porque, quando o pool descarta a conexão após o timeout da trava,
            // o rollback falha e a exceção do lock chega trocada por JpaSystemException. Se o banco estiver fora
            // de fato, o fallback() abaixo falha de novo e a exceção propaga.
            if (nimbusRespondeu.get()) {
                log.error("Falha ao gravar o catálogo após busca bem-sucedida ({})", e.getClass().getSimpleName());
            } else {
                log.warn("Não foi possível obter a trava do catálogo ({})", e.getClass().getSimpleName());
            }
            return fallback();
        }
        if (atual.isPresent()) {
            cache.guardar(atual.get());
            return new CatalogoResultado(atual.get(), false);
        }
        return fallback();
    }

    private static boolean dentroDoIntervaloAposFalha(Instant ultimaFalhaEm, Instant agora) {
        return ultimaFalhaEm != null && agora.isBefore(ultimaFalhaEm.plus(INTERVALO_APOS_FALHA));
    }

    /**
     * Roda com a linha de controle travada. Devolve o catálogo vigente (de outra instância ou recém-buscado)
     * ou vazio quando a Nimbus falhou ou está no intervalo após falha; nesse caso a transação ainda faz commit,
     * para que {@code ultimaFalhaEm} fique gravado.
     */
    private Optional<CatalogoSnapshot> buscarComTrava(AtomicBoolean nimbusRespondeu) {
        ControleCatalogo c = controle.travar();
        Instant agora = clock.instant();

        Optional<CatalogoSnapshot> gravado = snapshotDoSql();
        if (gravado.isPresent() && gravado.get().vigente(agora)) {
            return gravado; // outra requisição/instância acabou de buscar
        }
        if (dentroDoIntervaloAposFalha(c.getUltimaFalhaEm(), agora)) {
            return Optional.empty();
        }

        List<RegiaoNimbus> recebidas;
        try {
            recebidas = nimbus.listarRegioes();
        } catch (NimbusIndisponivelException e) {
            log.warn("Busca do catálogo na Nimbus falhou (motivo={}); servindo o catálogo gravado, se houver",
                    e.motivo());
            c.registrarFalha(agora);
            return Optional.empty();
        }
        nimbusRespondeu.set(true);

        List<Regiao> gravadas = regioes.findAll();
        regioes.saveAll(ReconciliacaoCatalogo.aplicar(gravadas, recebidas, agora));
        buscas.save(new BuscaCatalogo(agora, recebidas.size()));
        c.limparFalha();
        return snapshotDoSql();
    }

    private CatalogoResultado fallback() {
        CatalogoSnapshot gravado = snapshotDoSql().orElseThrow(FonteIndisponivelException::new);
        log.warn("Servindo catálogo de regiões desatualizado (obtidoEm={})", gravado.obtidoEm());
        return new CatalogoResultado(gravado, true);
    }

    /** Regiões não removidas + {@code obtidoEm} da última busca; vazio se nunca houve busca bem-sucedida. */
    private Optional<CatalogoSnapshot> snapshotDoSql() {
        return buscas.findTopByOrderByObtidoEmDesc()
                .map(b -> new CatalogoSnapshot(
                        regioes.findAllByRemovidaFalseOrderByRegionId().stream().map(Regiao::resumo).toList(),
                        b.getObtidoEm()));
    }
}

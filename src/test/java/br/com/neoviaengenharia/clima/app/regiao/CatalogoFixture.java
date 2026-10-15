package br.com.neoviaengenharia.clima.app.regiao;

import br.com.neoviaengenharia.clima.domain.regiao.CatalogoSnapshot;
import br.com.neoviaengenharia.clima.domain.regiao.Regiao;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoNimbus;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogo;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogoRepository;
import br.com.neoviaengenharia.clima.infra.repo.ControleCatalogo;
import br.com.neoviaengenharia.clima.infra.repo.ControleCatalogoRepository;
import br.com.neoviaengenharia.clima.infra.repo.RegiaoRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Preparação do estado (SQL, cache e Nimbus simulado) para os testes do catálogo. */
public class CatalogoFixture {

    /** Valor padrão de {@code DEFAULT_LOCK_TIMEOUT} no H2 2.x (ms); o H2 não o expõe em INFORMATION_SCHEMA. */
    private static final int LOCK_TIMEOUT_PADRAO_DO_H2 = 1000;

    final RegiaoRepository regioes;
    final BuscaCatalogoRepository buscas;
    final ControleCatalogoRepository controle;
    private final TransactionTemplate tx;
    private final Cache<String, CatalogoSnapshot> l1;
    private final StringRedisTemplate redis;
    private final HikariDataSource dataSource;

    @SuppressWarnings("unchecked")
    public CatalogoFixture(ApplicationContext ctx) {
        this.regioes = ctx.getBean(RegiaoRepository.class);
        this.buscas = ctx.getBean(BuscaCatalogoRepository.class);
        this.controle = ctx.getBean(ControleCatalogoRepository.class);
        this.tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
        this.l1 = ctx.getBean("catalogoL1", Cache.class);
        this.redis = ctx.getBean(StringRedisTemplate.class);
        this.dataSource = ctx.getBean(HikariDataSource.class);
    }

    Instant ultimaFalhaEm() {
        return tx.execute(s -> controle.travar().getUltimaFalhaEm());
    }

    /** Grava {@code ultimaFalhaEm} como se uma busca tivesse falhado em {@code quando}. */
    void registrarFalha(Instant quando) {
        tx.executeWithoutResult(s -> controle.travar().registrarFalha(quando));
    }

    /**
     * Trava a linha de controle numa transação de outra thread até {@link Trava#close()}, simulando outra
     * instância presa numa busca lenta na Nimbus.
     */
    Trava travarControle() throws InterruptedException {
        return travarControle(c -> {});
    }

    /** Como {@link #travarControle()}, mas aplica {@code antesDeSoltar} à linha, na mesma transação, antes do commit. */
    Trava travarControle(Consumer<ControleCatalogo> antesDeSoltar) throws InterruptedException {
        CountDownLatch obtida = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        Thread dono = new Thread(() -> tx.executeWithoutResult(s -> {
            ControleCatalogo c = controle.travar();
            obtida.countDown();
            try {
                soltar.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            antesDeSoltar.accept(c);
        }), "dono-da-trava");
        dono.start();
        if (!obtida.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("A thread dona não obteve a trava");
        }
        return () -> {
            soltar.countDown();
            try {
                dono.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /** Espera até alguma sessão do H2 estar bloqueada esperando uma trava. */
    void aguardarSessaoBloqueada() throws InterruptedException {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < limite) {
            Integer bloqueadas = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL", Integer.class);
            if (bloqueadas != null && bloqueadas > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("Nenhuma sessão ficou esperando a trava");
    }

    /** Roda {@code acao} numa transação externa: um único contexto de persistência, como no open-in-view. */
    <T> T emUmContextoDePersistencia(Supplier<T> acao) {
        return tx.execute(s -> acao.get());
    }

    interface Trava extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Baixa o lock timeout do H2 (o Hibernate ignora a dica de timeout no H2) para as conexões novas do pool,
     * até {@link Trava#close()}, que restaura o padrão do H2 (a URL de teste não o altera).
     */
    Trava lockTimeoutDoH2(int millis) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("SET DEFAULT_LOCK_TIMEOUT " + millis);
        dataSource.getHikariPoolMXBean().softEvictConnections();
        return () -> {
            jdbc.execute("SET DEFAULT_LOCK_TIMEOUT " + LOCK_TIMEOUT_PADRAO_DO_H2);
            dataSource.getHikariPoolMXBean().softEvictConnections();
        };
    }

    /** Esvazia tabelas, zera a última falha, limpa L1/L2 e descarta respostas enfileiradas no Nimbus. */
    public void limpar(MockWebServer nimbus) {
        nimbus.setDispatcher(new QueueDispatcher());
        tx.executeWithoutResult(s -> {
            regioes.deleteAll();
            buscas.deleteAll();
            controle.travar().limparFalha();
        });
        l1.invalidateAll();
        try {
            redis.delete("clima:regioes:catalogo");
        } catch (Exception e) {
            // cenário "Redis fora": não há o que limpar
        }
    }

    /** Grava direto no SQL um catálogo obtido em {@code obtidoEm}. */
    void gravar(Instant obtidoEm, RegiaoNimbus... itens) {
        tx.executeWithoutResult(s -> {
            for (RegiaoNimbus item : itens) {
                regioes.save(Regiao.nova(item, obtidoEm));
            }
            buscas.save(new BuscaCatalogo(obtidoEm, itens.length));
        });
    }

    /** Grava uma região já marcada como removida. */
    void gravarRemovida(Instant quando, RegiaoNimbus item) {
        tx.executeWithoutResult(s -> {
            Regiao r = Regiao.nova(item, quando);
            r.marcarRemovida(quando);
            regioes.save(r);
        });
    }

    public static RegiaoNimbus ativa(long id) {
        return new RegiaoNimbus(id, "Região " + id, "r" + id, false, 25L);
    }

    static RegiaoNimbus inativa(long id) {
        return new RegiaoNimbus(id, "Região " + id, "r" + id, true, 25L);
    }

    public static MockResponse resposta(RegiaoNimbus... itens) {
        String corpo = Stream.of(itens)
                .map(r -> String.format(
                        "{\"region_id\":%d,\"name\":\"%s\",\"alias\":\"%s\",\"inactive\":%s,\"state_id\":%d}",
                        r.regionId(), r.nome(), r.alias(), r.inativa(), r.estadoId()))
                .collect(Collectors.joining(",", "[", "]"));
        return json(corpo);
    }

    static MockResponse json(String corpo) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(corpo);
    }
}

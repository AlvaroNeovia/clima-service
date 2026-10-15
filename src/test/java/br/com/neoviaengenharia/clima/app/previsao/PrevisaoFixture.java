package br.com.neoviaengenharia.clima.app.previsao;

import br.com.neoviaengenharia.clima.domain.previsao.PontoPrevisao;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaPrevisao;
import br.com.neoviaengenharia.clima.domain.regiao.Regiao;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoNimbus;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogo;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogoRepository;
import br.com.neoviaengenharia.clima.infra.repo.PrevisaoTabelas;
import br.com.neoviaengenharia.clima.infra.repo.RegiaoRepository;
import br.com.neoviaengenharia.clima.infra.repo.RodadaPrevisaoRepositorio;
import br.com.neoviaengenharia.clima.infra.repo.TentativaPrevisao;
import br.com.neoviaengenharia.clima.infra.repo.TentativaPrevisaoRepository;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Estado (SQL, caches e Nimbus simulado) para os testes da previsão vigente. */
class PrevisaoFixture {

    final RodadaPrevisaoRepositorio rodadas;
    final TentativaPrevisaoRepository tentativas;
    final JdbcTemplate jdbc;
    private final RegiaoRepository regioes;
    private final BuscaCatalogoRepository buscas;
    private final TransactionTemplate tx;
    private final List<Cache<?, ?>> l1s;
    private final StringRedisTemplate redis;

    PrevisaoFixture(ApplicationContext ctx) {
        this.rodadas = ctx.getBean(RodadaPrevisaoRepositorio.class);
        this.tentativas = ctx.getBean(TentativaPrevisaoRepository.class);
        this.jdbc = ctx.getBean(JdbcTemplate.class);
        this.regioes = ctx.getBean(RegiaoRepository.class);
        this.buscas = ctx.getBean(BuscaCatalogoRepository.class);
        this.tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
        this.l1s = List.of(ctx.getBean("catalogoL1", Cache.class), ctx.getBean("previsaoVigenteL1", Cache.class));
        this.redis = ctx.getBean(StringRedisTemplate.class);
    }

    /** Esvazia tabelas do S1/S2, L1/L2, a fila de respostas e as requisições já gravadas do Nimbus. */
    void limpar(MockWebServer nimbus) throws InterruptedException {
        nimbus.setDispatcher(new QueueDispatcher());
        while (nimbus.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS) != null) {
            // descarta requisições de testes anteriores
        }
        PrevisaoTabelas.limparComRegioes(jdbc);
        buscas.deleteAll();
        l1s.forEach(Cache::invalidateAll);
        try {
            Set<String> chaves = redis.keys("clima:*");
            if (chaves != null && !chaves.isEmpty()) {
                redis.delete(chaves);
            }
        } catch (Exception e) {
            // cenário "Redis fora"
        }
    }

    /** Catálogo vigente obtido em {@code obtidoEm}. */
    void semearCatalogo(Instant obtidoEm, RegiaoNimbus... itens) {
        tx.executeWithoutResult(s -> {
            for (RegiaoNimbus item : itens) {
                regioes.save(Regiao.nova(item, obtidoEm));
            }
            buscas.save(new BuscaCatalogo(obtidoEm, itens.length));
        });
    }

    void gravarRodada(long regionId, String run, String obtidoEm, PontoPrevisao... pontos) {
        rodadas.gravar(new RodadaPrevisao(regionId, Instant.parse(run), Instant.parse(obtidoEm), List.of(pontos)));
    }

    void registrarTentativa(long regionId, Instant quando, boolean falhou) {
        tentativas.garantir(regionId);
        tx.executeWithoutResult(s -> tentativas.findById(regionId).orElseThrow().registrar(quando, falhou));
    }

    TentativaPrevisao tentativa(long regionId) {
        return tentativas.findById(regionId).orElseThrow();
    }

    int contarRodadas() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM clima.rodada_previsao", Integer.class);
    }

    void semearChaveRedis(String chave) {
        redis.opsForValue().set(chave, "{}");
    }

    boolean existeChaveRedis(String chave) {
        return Boolean.TRUE.equals(redis.hasKey(chave));
    }

    static RegiaoNimbus ativa(long id) {
        return new RegiaoNimbus(id, "Região " + id, "r" + id, false, 25L);
    }

    static RegiaoNimbus inativa(long id) {
        return new RegiaoNimbus(id, "Região " + id, "r" + id, true, 25L);
    }

    /** Item de resposta da Nimbus: um ponto por {@code datas}, todos com {@code run} e as variáveis dadas (JSON). */
    static String regiao(long id, String run, String variaveisJson, String... datas) {
        String pontos = Stream.of(datas)
                .map(d -> String.format("{\"datetime\":%d,\"run_datetime\":%d%s}", Instant.parse(d).toEpochMilli(),
                        Instant.parse(run).toEpochMilli(), variaveisJson.isEmpty() ? "" : "," + variaveisJson))
                .collect(Collectors.joining(","));
        return String.format("{\"region_id\":%d,\"forecasts\":[%s]}", id, pontos);
    }

    static String semDado(long id) {
        return String.format("{\"region_id\":%d,\"forecasts\":[]}", id);
    }

    static MockResponse resposta(String... itens) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(Stream.of(itens).collect(Collectors.joining(",", "[", "]")));
    }

    /** Datas ISO horárias de {@code de} a {@code ate}, inclusive. */
    static String[] horarios(String de, String ate) {
        List<String> datas = new ArrayList<>();
        for (Instant t = Instant.parse(de); !t.isAfter(Instant.parse(ate)); t = t.plus(Duration.ofHours(1))) {
            datas.add(t.toString());
        }
        return datas.toArray(String[]::new);
    }

    static String corpo(RecordedRequest req) {
        return req.getBody().clone().readUtf8();
    }
}

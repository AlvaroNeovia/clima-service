package br.com.neoviaengenharia.clima.app.regiao;

import br.com.neoviaengenharia.clima.domain.regiao.CatalogoSnapshot;
import br.com.neoviaengenharia.clima.domain.regiao.Regiao;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoNimbus;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogo;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogoRepository;
import br.com.neoviaengenharia.clima.infra.repo.ControleCatalogoRepository;
import br.com.neoviaengenharia.clima.infra.repo.RegiaoRepository;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Instant;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Preparação do estado (SQL, cache e Nimbus simulado) para os testes do catálogo. */
class CatalogoFixture {

    final RegiaoRepository regioes;
    final BuscaCatalogoRepository buscas;
    final ControleCatalogoRepository controle;
    private final TransactionTemplate tx;
    private final Cache<String, CatalogoSnapshot> l1;
    private final StringRedisTemplate redis;

    @SuppressWarnings("unchecked")
    CatalogoFixture(ApplicationContext ctx) {
        this.regioes = ctx.getBean(RegiaoRepository.class);
        this.buscas = ctx.getBean(BuscaCatalogoRepository.class);
        this.controle = ctx.getBean(ControleCatalogoRepository.class);
        this.tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
        this.l1 = ctx.getBean("catalogoL1", Cache.class);
        this.redis = ctx.getBean(StringRedisTemplate.class);
    }

    Instant ultimaFalhaEm() {
        return tx.execute(s -> controle.travar().getUltimaFalhaEm());
    }

    /** Esvazia tabelas, zera a última falha, limpa L1/L2 e descarta respostas enfileiradas no Nimbus. */
    void limpar(MockWebServer nimbus) {
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

    static RegiaoNimbus ativa(long id) {
        return new RegiaoNimbus(id, "Região " + id, "r" + id, false, 25L);
    }

    static RegiaoNimbus inativa(long id) {
        return new RegiaoNimbus(id, "Região " + id, "r" + id, true, 25L);
    }

    static MockResponse resposta(RegiaoNimbus... itens) {
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

package br.com.neoviaengenharia.clima.app.regiao;

import static br.com.neoviaengenharia.clima.app.regiao.CatalogoFixture.ativa;
import static br.com.neoviaengenharia.clima.app.regiao.CatalogoFixture.inativa;
import static br.com.neoviaengenharia.clima.app.regiao.CatalogoFixture.json;
import static br.com.neoviaengenharia.clima.app.regiao.CatalogoFixture.resposta;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.neoviaengenharia.clima.domain.regiao.RegiaoResumo;
import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import br.com.neoviaengenharia.clima.support.RelogioMutavel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
class CatalogoRegioesServiceIT extends IntegracaoBase {

    private static final Instant AGORA = RelogioMutavel.INSTANTE_PADRAO; // 2026-10-15T12:00:00Z

    @Autowired
    CatalogoRegioesService service;

    @Autowired
    ApplicationContext ctx;

    CatalogoFixture fx;
    int requisicoesAntes;

    @BeforeEach
    void preparar() {
        relogio.definir(AGORA);
        fx = new CatalogoFixture(ctx);
        fx.limpar(nimbus);
        requisicoesAntes = nimbus.getRequestCount();
    }

    private int novasRequisicoes() {
        return nimbus.getRequestCount() - requisicoesAntes;
    }

    private static List<Long> ids(CatalogoResultado r) {
        return r.snapshot().regioes().stream().map(RegiaoResumo::regionId).toList();
    }

    @Test
    void primeiraBuscaGravaEResponde() {
        nimbus.enqueue(resposta(ativa(1359), ativa(1706)));

        CatalogoResultado r = service.obter();

        assertThat(r.desatualizado()).isFalse();
        assertThat(ids(r)).containsExactly(1359L, 1706L);
        assertThat(r.snapshot().obtidoEm()).isEqualTo(AGORA);
        assertThat(fx.regioes.count()).isEqualTo(2);
        assertThat(fx.buscas.count()).isEqualTo(1);
        assertThat(fx.buscas.findTopByOrderByObtidoEmDesc().orElseThrow().getTotalRegioes()).isEqualTo(2);
        assertThat(novasRequisicoes()).isEqualTo(1);
    }

    @Test
    void catalogoEmCacheNaoChamaNimbus() {
        nimbus.enqueue(resposta(ativa(1359), ativa(1706)));
        service.obter();
        relogio.definir(Instant.parse("2026-10-15T14:00:00Z"));

        CatalogoResultado r = service.obter();

        assertThat(r.desatualizado()).isFalse();
        assertThat(ids(r)).containsExactly(1359L, 1706L);
        assertThat(r.snapshot().obtidoEm()).isEqualTo(AGORA);
        assertThat(novasRequisicoes()).isEqualTo(1);
    }

    @Test
    void catalogoVencidoBuscaDeNovo() {
        nimbus.enqueue(resposta(ativa(1359)));
        service.obter();
        relogio.avancar(Duration.ofHours(25));
        nimbus.enqueue(resposta(ativa(1359), ativa(1706)));

        CatalogoResultado r = service.obter();

        assertThat(novasRequisicoes()).isEqualTo(2);
        assertThat(r.desatualizado()).isFalse();
        assertThat(r.snapshot().obtidoEm()).isEqualTo(AGORA.plus(Duration.ofHours(25)));
        assertThat(ids(r)).containsExactly(1359L, 1706L);
        assertThat(fx.buscas.count()).isEqualTo(2);
    }

    @Test
    void atualizarDentroDe24hNaoChama() {
        Instant tresHorasAtras = AGORA.minus(Duration.ofHours(3));
        fx.gravar(tresHorasAtras, ativa(1359), ativa(1706));

        CatalogoResultado r = service.atualizar();

        assertThat(novasRequisicoes()).isZero();
        assertThat(r.desatualizado()).isFalse();
        assertThat(r.snapshot().obtidoEm()).isEqualTo(tresHorasAtras);
        assertThat(ids(r)).containsExactly(1359L, 1706L);
    }

    @Test
    void atualizarComCatalogoVencidoBusca() {
        fx.gravar(AGORA.minus(Duration.ofHours(25)), ativa(1359));
        nimbus.enqueue(resposta(ativa(1359), ativa(1706)));

        CatalogoResultado r = service.atualizar();

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(r.snapshot().obtidoEm()).isEqualTo(AGORA);
        assertThat(ids(r)).containsExactly(1359L, 1706L);
    }

    @Test
    void removidaSaiDaLista() {
        fx.gravar(AGORA.minus(Duration.ofHours(25)), ativa(1359), ativa(1706));
        nimbus.enqueue(resposta(ativa(1359)));

        CatalogoResultado r = service.obter();

        assertThat(ids(r)).containsExactly(1359L);
        assertThat(fx.regioes.findById(1706L).orElseThrow().isRemovida()).isTrue();
        assertThat(service.obter().snapshot().regioes()).extracting(RegiaoResumo::regionId).containsExactly(1359L);
    }

    @Test
    void inativaApareceNaLista() {
        nimbus.enqueue(resposta(ativa(1359), inativa(1706)));

        CatalogoResultado r = service.obter();

        assertThat(r.snapshot().regioes())
                .filteredOn(x -> x.regionId() == 1706L)
                .singleElement()
                .satisfies(x -> assertThat(x.inativa()).isTrue());
    }

    @Test
    void nimbusForaComCatalogo() {
        Instant trintaHorasAtras = AGORA.minus(Duration.ofHours(30));
        fx.gravar(trintaHorasAtras, ativa(1359), ativa(1706));
        nimbus.enqueue(new MockResponse().setResponseCode(503));

        CatalogoResultado r = service.obter();

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(r.desatualizado()).isTrue();
        assertThat(r.snapshot().obtidoEm()).isEqualTo(trintaHorasAtras);
        assertThat(ids(r)).containsExactly(1359L, 1706L);
        assertThat(fx.ultimaFalhaEm()).isEqualTo(AGORA);
    }

    @Test
    void nimbusForaSemCatalogo() {
        nimbus.enqueue(new MockResponse().setResponseCode(503));

        assertThatThrownBy(() -> service.obter())
                .isInstanceOf(FonteIndisponivelException.class)
                .hasMessage("Fonte de dados climáticos indisponível");

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(fx.ultimaFalhaEm()).isEqualTo(AGORA);
        assertThat(fx.buscas.count()).isZero();
    }

    @Test
    void credencialRecusadaLogaErro(CapturedOutput saida) {
        fx.gravar(AGORA.minus(Duration.ofHours(30)), ativa(1359));
        nimbus.enqueue(new MockResponse().setResponseCode(401));

        CatalogoResultado r = service.obter();

        assertThat(r.desatualizado()).isTrue();
        assertThat(saida.getAll()).contains("ERROR").contains("recusou as credenciais");
        assertThat(saida.getAll()).doesNotContain("chave-publica-teste").doesNotContain("chave-privada-teste");
    }

    @Test
    void listaVaziaNaoGravaEMarcaDesatualizado(CapturedOutput saida) {
        Instant obtidoEm = AGORA.minus(Duration.ofHours(25));
        fx.gravar(obtidoEm, ativa(1359), ativa(1706));
        nimbus.enqueue(json("[]"));

        CatalogoResultado r = service.obter();

        assertThat(r.desatualizado()).isTrue();
        assertThat(r.snapshot().obtidoEm()).isEqualTo(obtidoEm);
        assertThat(ids(r)).containsExactly(1359L, 1706L);
        assertThat(fx.regioes.findAllByRemovidaFalseOrderByRegionId()).hasSize(2);
        assertThat(fx.buscas.count()).isEqualTo(1);
        assertThat(saida.getAll()).contains("WARN").contains("CORPO_INVALIDO");
    }

    @Test
    void falhaRespeitaIntervaloDe15Minutos() {
        fx.gravar(AGORA.minus(Duration.ofHours(25)), ativa(1359));
        nimbus.enqueue(new MockResponse().setResponseCode(503));
        assertThat(service.obter().desatualizado()).isTrue();
        assertThat(novasRequisicoes()).isEqualTo(1);

        relogio.definir(Instant.parse("2026-10-15T12:10:00Z"));
        CatalogoResultado r = service.obter();
        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(r.desatualizado()).isTrue();

        relogio.definir(Instant.parse("2026-10-15T12:16:00Z"));
        nimbus.enqueue(resposta(ativa(1359)));
        r = service.obter();
        assertThat(novasRequisicoes()).isEqualTo(2);
        assertThat(r.desatualizado()).isFalse();
        assertThat(r.snapshot().obtidoEm()).isEqualTo(Instant.parse("2026-10-15T12:16:00Z"));
        assertThat(fx.ultimaFalhaEm()).isNull();
    }

    @Test
    void concorrenciaFazUmaBusca() throws Exception {
        fx.gravar(AGORA.minus(Duration.ofHours(25)), ativa(1359));
        // uma resposta lenta; as demais só existem para uma implementação errada não travar o teste
        nimbus.enqueue(resposta(ativa(1359), ativa(1706)).setHeadersDelay(300, TimeUnit.MILLISECONDS));
        for (int i = 0; i < 7; i++) {
            nimbus.enqueue(resposta(ativa(1359), ativa(1706)));
        }

        int threads = 8;
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<CatalogoResultado>> futuros = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return service.obter();
                }));
            }
            largada.countDown();
            for (Future<CatalogoResultado> f : futuros) {
                CatalogoResultado r = f.get(30, TimeUnit.SECONDS);
                assertThat(r.desatualizado()).isFalse();
                assertThat(ids(r)).containsExactly(1359L, 1706L);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(fx.buscas.count()).isEqualTo(2);
    }
}

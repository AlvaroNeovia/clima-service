package br.com.neoviaengenharia.clima.app.observado;

import static br.com.neoviaengenharia.clima.app.observado.ObservadoFixture.ATIVA;
import static br.com.neoviaengenharia.clima.app.observado.ObservadoFixture.INATIVA;
import static br.com.neoviaengenharia.clima.app.observado.ObservadoFixture.REMOVIDA;
import static br.com.neoviaengenharia.clima.app.observado.ObservadoFixture.horas;
import static br.com.neoviaengenharia.clima.app.observado.ObservadoFixture.observado;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import br.com.neoviaengenharia.clima.app.regiao.FonteIndisponivelException;
import br.com.neoviaengenharia.clima.domain.observado.EstadoHora;
import br.com.neoviaengenharia.clima.domain.observado.JanelaObservada;
import br.com.neoviaengenharia.clima.infra.repo.HoraObservada;
import br.com.neoviaengenharia.clima.infra.repo.TravaRegiaoRepository;
import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.PessimisticLockingFailureException;

@ExtendWith(OutputCaptureExtension.class)
class PrecipitacaoObservadaServiceIT extends IntegracaoBase {

    static final Instant AGORA = Instant.parse("2026-10-15T18:20:00Z");
    static final Instant DIA_10 = Instant.parse("2026-10-10T00:00:00Z");
    static final Instant DIA_14 = Instant.parse("2026-10-14T00:00:00Z");
    static final Instant DIA_15 = Instant.parse("2026-10-15T00:00:00Z");

    @Autowired
    PrecipitacaoObservadaService service;

    @SpyBean
    TravaRegiaoRepository trava;

    @Autowired
    ApplicationContext ctx;

    ObservadoFixture fx;
    int requisicoesAntes;

    @BeforeEach
    void preparar() {
        relogio.definir(AGORA);
        fx = new ObservadoFixture(ctx);
        fx.limpar(nimbus);
        fx.catalogo(AGORA);
        requisicoesAntes = nimbus.getRequestCount();
    }

    @AfterEach
    void limpar() {
        fx.limpar(nimbus);
    }

    private int novasRequisicoes() {
        return nimbus.getRequestCount() - requisicoesAntes;
    }

    private static Instant h15(String hora) {
        return Instant.parse("2026-10-15T" + hora + ":00Z");
    }

    private static JanelaObservada janela(Instant inicio, Instant fim) {
        return new JanelaObservada(inicio, fim);
    }

    private static JanelaObservada dia(Instant inicio) {
        return janela(inicio, inicio.plusSeconds(86_400));
    }

    private static List<Instant> datas(ObservadoResultado r) {
        return r.horas().stream().map(EstadoHora::dataHora).toList();
    }

    private static BigDecimal soma(ObservadoResultado r, Instant h) {
        return r.horas().stream().filter(e -> e.dataHora().equals(h)).findFirst().orElseThrow()
                .leitura().precipitacaoAcumulada();
    }

    private RecordedRequest ultimaRequisicao() throws InterruptedException {
        RecordedRequest req = null;
        for (RecordedRequest r = nimbus.takeRequest(100, TimeUnit.MILLISECONDS); r != null;
                r = nimbus.takeRequest(100, TimeUnit.MILLISECONDS)) {
            req = r;
        }
        return req;
    }

    private static String query(Instant inicio, Instant fim) {
        return "start_date=" + inicio.toEpochMilli() + "&end_date=" + fim.toEpochMilli();
    }

    @Test
    void janelaDefinitivaNaoChamaNimbus() {
        fx.gravar(ATIVA, horas(DIA_14, 24), "0", DIA_15);

        ObservadoResultado r = service.consultar(ATIVA, dia(DIA_14));

        assertThat(r.horas()).hasSize(24);
        assertThat(r.desatualizado()).isFalse();
        assertThat(datas(r)).isSorted();
        assertThat(novasRequisicoes()).isZero();
    }

    @Test
    void primeiraConsultaDeJanelaAntiga() throws InterruptedException {
        List<Instant> devolvidas = new ArrayList<>(horas(DIA_10, 24));
        devolvidas.remove(DIA_10.plusSeconds(5 * 3600));
        nimbus.enqueue(observado(ATIVA, devolvidas, "0"));

        ObservadoResultado r = service.consultar(ATIVA, dia(DIA_10));

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(ultimaRequisicao().getPath())
                .contains("/manage/representative-stations/1674/data-consistency")
                .contains("start_date=1791590400000&end_date=1791676800000");
        assertThat(r.horas()).hasSize(23);
        assertThat(datas(r)).doesNotContain(DIA_10.plusSeconds(5 * 3600));
        List<HoraObservada> gravadas = fx.horasGravadas(ATIVA);
        assertThat(gravadas).hasSize(24);
        assertThat(gravadas).filteredOn(HoraObservada::isSemDado).singleElement()
                .satisfies(h -> assertThat(h.getDataHora()).isEqualTo(DIA_10.plusSeconds(5 * 3600)));
    }

    @Test
    void semDadoNaoVoltaANimbus() {
        List<Instant> comDado = new ArrayList<>(horas(DIA_10, 24));
        comDado.remove(DIA_10.plusSeconds(5 * 3600));
        fx.gravar(ATIVA, comDado, "0", DIA_15);
        fx.gravarSemDado(ATIVA, DIA_10.plusSeconds(5 * 3600), DIA_15);

        ObservadoResultado r = service.consultar(ATIVA, dia(DIA_10));

        assertThat(novasRequisicoes()).isZero();
        assertThat(r.horas()).hasSize(23);
    }

    @Test
    void reconciliacaoDasUltimas6h() throws InterruptedException {
        fx.gravar(ATIVA, horas(h15("06:00"), 6), "1", h15("12:30"));
        fx.gravar(ATIVA, horas(h15("12:00"), 6), "1", h15("17:10"));
        nimbus.enqueue(observado(ATIVA, horas(h15("12:00"), 7), "9"));

        ObservadoResultado r = service.consultar(ATIVA, janela(h15("06:00"), h15("19:00")));

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(ultimaRequisicao().getPath()).contains(query(h15("12:00"), h15("19:00")));
        assertThat(r.horas()).hasSize(13);
        assertThat(soma(r, h15("06:00"))).isEqualTo(new BigDecimal("1"));
        assertThat(soma(r, h15("12:00"))).isEqualTo(new BigDecimal("9"));
        assertThat(soma(r, h15("18:00"))).isEqualTo(new BigDecimal("9"));
        assertThat(fx.tentativa(ATIVA, h15("12:00"))).isEqualTo(AGORA);
    }

    @Test
    void limiteDeUmaVezPorHora() {
        fx.gravar(ATIVA, horas(h15("12:00"), 7), "1", h15("18:00"));

        ObservadoResultado r = service.consultar(ATIVA, janela(h15("12:00"), h15("19:00")));

        assertThat(novasRequisicoes()).isZero();
        assertThat(r.horas()).hasSize(7);
    }

    @Test
    void recenteAusenteTentaDeNovo() throws InterruptedException {
        List<Instant> gravadas = new ArrayList<>(horas(h15("12:00"), 6));
        gravadas.remove(h15("16:00"));
        fx.gravar(ATIVA, gravadas, "1", h15("17:10"));
        fx.gravarTentativa(ATIVA, h15("16:00"), h15("17:10"));
        List<Instant> devolvidas = new ArrayList<>(horas(h15("12:00"), 7));
        devolvidas.remove(h15("16:00"));
        nimbus.enqueue(observado(ATIVA, devolvidas, "2"));

        ObservadoResultado r = service.consultar(ATIVA, janela(h15("12:00"), h15("19:00")));

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(ultimaRequisicao().getPath()).contains(query(h15("12:00"), h15("19:00")));
        assertThat(datas(r)).doesNotContain(h15("16:00"));
        assertThat(fx.horasGravadas(ATIVA)).noneMatch(h -> h.getDataHora().equals(h15("16:00")));
        assertThat(fx.tentativa(ATIVA, h15("16:00"))).isEqualTo(AGORA);
    }

    @Test
    void recenteQueEnvelheceViraSemDado() {
        fx.gravarTentativa(ATIVA, h15("11:00"), h15("17:10"));
        nimbus.enqueue(observado(ATIVA, List.of(), "0"));

        ObservadoResultado r = service.consultar(ATIVA, janela(h15("11:00"), h15("12:00")));

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(r.horas()).isEmpty();
        assertThat(fx.horasGravadas(ATIVA)).singleElement().satisfies(h -> assertThat(h.isSemDado()).isTrue());
    }

    @Test
    void janelaNoFuturo() throws InterruptedException {
        nimbus.enqueue(observado(ATIVA, horas(h15("12:00"), 7), "0"));

        ObservadoResultado r = service.consultar(ATIVA, janela(h15("12:00"), Instant.parse("2026-10-16T00:00:00Z")));

        assertThat(ultimaRequisicao().getPath()).contains(query(h15("12:00"), h15("19:00")));
        assertThat(datas(r)).hasSize(7).endsWith(h15("18:00"));
    }

    @Test
    void janelaTodaNoFuturoNaoChamaNimbus() {
        ObservadoResultado r = service.consultar(ATIVA, dia(Instant.parse("2026-10-16T00:00:00Z")));

        assertThat(r.horas()).isEmpty();
        assertThat(novasRequisicoes()).isZero();
    }

    @Test
    void estacaoVariavel() {
        Instant meioDia = DIA_10.plusSeconds(12 * 3600);
        nimbus.enqueue(observado(ATIVA, horas(DIA_10, 24), h -> h.isBefore(meioDia) ? 3078L : 3090L, "0"));

        ObservadoResultado r = service.consultar(ATIVA, dia(DIA_10));

        assertThat(r.horas().get(11).leitura().estacao().id()).isEqualTo(3078);
        assertThat(r.horas().get(12).leitura().estacao().id()).isEqualTo(3090);
        assertThat(r.horas().get(12).leitura().estacao().nome()).isEqualTo("Estação 3090");
    }

    @Test
    void inativaServeGravadoSemNimbus() {
        fx.gravar(INATIVA, horas(DIA_10, 10), "0", DIA_15);

        ObservadoResultado r = service.consultar(INATIVA, dia(DIA_10));

        assertThat(r.horas()).hasSize(10);
        assertThat(r.desatualizado()).isFalse();
        assertThat(novasRequisicoes()).isZero();
    }

    @Test
    void removidaServeGravadoSemNimbus() {
        fx.gravar(REMOVIDA, horas(h15("12:00"), 3), "0", h15("13:00"));

        ObservadoResultado r = service.consultar(REMOVIDA, janela(h15("12:00"), h15("19:00")));

        assertThat(r.horas()).hasSize(3);
        assertThat(novasRequisicoes()).isZero();
    }

    @Test
    void inativaSemNadaDevolveVazio() {
        ObservadoResultado r = service.consultar(INATIVA, dia(DIA_10));

        assertThat(r.horas()).isEmpty();
        assertThat(r.desatualizado()).isFalse();
        assertThat(novasRequisicoes()).isZero();
    }

    @Test
    void regiaoInexistente() {
        assertThatThrownBy(() -> service.consultar(99999, dia(DIA_10)))
                .isInstanceOf(RegiaoNaoEncontradaException.class)
                .hasMessage("Região não encontrada");
        assertThat(novasRequisicoes()).isZero();
    }

    @Test
    void nimbusForaComGravado(CapturedOutput saida) {
        fx.gravar(ATIVA, horas(h15("06:00"), 12), "1", h15("17:10"));
        nimbus.enqueue(new MockResponse().setResponseCode(503));

        ObservadoResultado r = service.consultar(ATIVA, janela(h15("06:00"), h15("19:00")));

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(r.desatualizado()).isTrue();
        assertThat(r.horas()).hasSize(12);
        assertThat(fx.horasGravadas(ATIVA)).noneMatch(HoraObservada::isSemDado);
        assertThat(fx.tentativa(ATIVA, h15("12:00"))).isEqualTo(h15("17:10"));
        assertThat(fx.tentativa(ATIVA, h15("18:00"))).isNull();
        assertThat(saida.getAll()).contains("desatualizado");
    }

    @Test
    void nimbusForaSemGravado() {
        nimbus.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        assertThatThrownBy(() -> service.consultar(ATIVA, dia(DIA_10)))
                .isInstanceOf(FonteIndisponivelException.class);
        assertThat(fx.horasGravadas(ATIVA)).isEmpty();
        assertThat(fx.tentativas.count()).isZero();
    }

    @Test
    void nimbusRecusaRegiao(CapturedOutput saida) {
        nimbus.enqueue(new MockResponse().setResponseCode(404));

        assertThatThrownBy(() -> service.consultar(ATIVA, dia(DIA_10)))
                .isInstanceOf(RegiaoNaoEncontradaException.class);
        assertThat(saida.getAll()).contains("WARN").contains("Nimbus recusou a região 1674");
        assertThat(fx.tentativas.count()).isZero();
    }

    @Test
    void segundaLeituraVemDoCache() {
        nimbus.enqueue(observado(ATIVA, horas(DIA_10, 24), "0"));
        ObservadoResultado primeira = service.consultar(ATIVA, dia(DIA_10));
        fx.apagarHoras();

        ObservadoResultado segunda = service.consultar(ATIVA, dia(DIA_10));

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(segunda).isEqualTo(primeira);
    }

    @Test
    void lockEsgotadoServeGravado() {
        fx.gravar(ATIVA, horas(h15("06:00"), 12), "1", h15("17:10"));
        doThrow(new PessimisticLockingFailureException("lock")).when(trava).travar(ATIVA);

        ObservadoResultado r = service.consultar(ATIVA, janela(h15("06:00"), h15("19:00")));

        assertThat(r.desatualizado()).isTrue();
        assertThat(r.horas()).hasSize(12);
        assertThat(novasRequisicoes()).isZero();
    }

    @Test
    void concorrenciaFazUmaChamada() throws Exception {
        nimbus.enqueue(observado(ATIVA, horas(DIA_10, 24), "0").setHeadersDelay(300, TimeUnit.MILLISECONDS));
        for (int i = 0; i < 7; i++) {
            nimbus.enqueue(observado(ATIVA, horas(DIA_10, 24), "0"));
        }

        int threads = 8;
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ObservadoResultado>> futuros = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return service.consultar(ATIVA, dia(DIA_10));
                }));
            }
            largada.countDown();
            for (Future<ObservadoResultado> f : futuros) {
                ObservadoResultado r = f.get(30, TimeUnit.SECONDS);
                assertThat(r.horas()).hasSize(24);
                assertThat(r.desatualizado()).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(fx.horasGravadas(ATIVA)).hasSize(24);
    }
}

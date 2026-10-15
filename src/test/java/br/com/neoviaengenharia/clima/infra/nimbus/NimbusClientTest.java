package br.com.neoviaengenharia.clima.infra.nimbus;

import br.com.neoviaengenharia.clima.domain.regiao.RegiaoNimbus;
import br.com.neoviaengenharia.clima.infra.nimbus.NimbusIndisponivelException.Motivo;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class NimbusClientTest {

    private static final String PUBLICA = "chave-publica-teste";
    private static final String PRIVADA = "chave-privada-teste";
    private static final String JSON_DOC = """
            [{"region_id":1359,"name":"01 SP-334 - Franca","alias":"f_neo_fra","inactive":false,"state_id":25},
             {"region_id":1706,"name":"01 Votorantim (Sorocaba)","alias":"f_neo_vot","inactive":false,"state_id":25}]
            """;

    private MockWebServer server;

    @BeforeEach
    void subir() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void descer() throws IOException {
        server.shutdown();
    }

    private NimbusClient cliente(Duration readTimeout) {
        NimbusProperties props = new NimbusProperties(server.url("/").uri(), PUBLICA, PRIVADA,
                Duration.ofSeconds(2), readTimeout);
        return new NimbusClient(NimbusConfig.criarRestClient(props));
    }

    private NimbusClient cliente() {
        return cliente(Duration.ofSeconds(5));
    }

    private void responder(int status, String corpo) {
        server.enqueue(new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(corpo));
    }

    @Test
    void enviaHeadersEIntegration() throws InterruptedException {
        responder(200, JSON_DOC);
        cliente().listarRegioes();
        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/forecast-regions?integration=true");
        assertThat(req.getHeader("api_key")).isEqualTo(PUBLICA);
        assertThat(req.getHeader("authorization")).isEqualTo(PRIVADA).doesNotStartWith("Bearer");
    }

    @Test
    void mapeiaRegioes() {
        responder(200, JSON_DOC);
        List<RegiaoNimbus> regioes = cliente().listarRegioes();
        assertThat(regioes).containsExactly(
                new RegiaoNimbus(1359, "01 SP-334 - Franca", "f_neo_fra", false, 25),
                new RegiaoNimbus(1706, "01 Votorantim (Sorocaba)", "f_neo_vot", false, 25));
    }

    @Test
    void ignoraCampoDesconhecido() {
        responder(200, """
                [{"region_id":1,"name":"A","alias":"a","inactive":false,"state_id":25,"extra":1}]""");
        assertThat(cliente().listarRegioes()).hasSize(1);
    }

    @Test
    void itemSemNomeEhCorpoInvalido() {
        responder(200, """
                [{"region_id":1,"alias":"a","inactive":false,"state_id":25}]""");
        assertMotivo(Motivo.CORPO_INVALIDO, null);
    }

    @Test
    void itemSemRegionIdEhCorpoInvalido() {
        responder(200, """
                [{"name":"A","alias":"a","inactive":false,"state_id":25}]""");
        assertMotivo(Motivo.CORPO_INVALIDO, null);
    }

    @Test
    void listaVaziaEhCorpoInvalido() {
        responder(200, "[]");
        assertMotivo(Motivo.CORPO_INVALIDO, null);
    }

    @Test
    void status401() {
        responder(401, "{}");
        assertMotivo(Motivo.CREDENCIAL_RECUSADA, 401);
    }

    @Test
    void status403() {
        responder(403, "{}");
        assertMotivo(Motivo.CREDENCIAL_RECUSADA, 403);
    }

    @Test
    void status503() {
        responder(503, "{}");
        assertMotivo(Motivo.ERRO_HTTP, 503);
    }

    @Test
    void timeout() {
        server.enqueue(new MockResponse().setBody(JSON_DOC).setBodyDelay(1, TimeUnit.SECONDS));
        assertThatThrownBy(() -> cliente(Duration.ofMillis(200)).listarRegioes())
                .isInstanceOfSatisfying(NimbusIndisponivelException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.TIMEOUT));
    }

    @Test
    void timeoutEsperandoHeaders() {
        server.enqueue(new MockResponse().setBody(JSON_DOC).setSocketPolicy(SocketPolicy.NO_RESPONSE));
        assertThatThrownBy(() -> cliente(Duration.ofMillis(200)).listarRegioes())
                .isInstanceOfSatisfying(NimbusIndisponivelException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.TIMEOUT));
    }

    @Test
    void timeoutEhRegistradoSemCredencial(CapturedOutput saida) {
        server.enqueue(new MockResponse().setBody(JSON_DOC).setSocketPolicy(SocketPolicy.NO_RESPONSE));
        assertThatThrownBy(() -> cliente(Duration.ofMillis(200)).listarRegioes())
                .isInstanceOf(NimbusIndisponivelException.class);
        assertThat(saida.getAll()).contains("forecast-regions").contains("TIMEOUT").contains("duracaoMs")
                .doesNotContain(PUBLICA).doesNotContain(PRIVADA);
    }

    @Test
    void mensagemTemStatusEntreColchetes() {
        responder(503, "{}");
        assertThatThrownBy(() -> cliente().listarRegioes())
                .hasMessage("Nimbus indisponível: ERRO_HTTP [503]");
    }

    @Test
    void mensagemDeErroNaoContemCredencial() {
        responder(401, "{}");
        assertThatThrownBy(() -> cliente().listarRegioes()).satisfies(this::semCredencial);
        server.enqueue(new MockResponse().setBody(JSON_DOC).setBodyDelay(1, TimeUnit.SECONDS));
        assertThatThrownBy(() -> cliente(Duration.ofMillis(200)).listarRegioes()).satisfies(this::semCredencial);
    }

    @Test
    void logNaoContemCredencial(CapturedOutput saida) {
        responder(200, JSON_DOC);
        cliente().listarRegioes();
        responder(401, "{}");
        assertThatThrownBy(() -> cliente().listarRegioes()).isInstanceOf(NimbusIndisponivelException.class);
        assertThat(saida.getAll()).doesNotContain(PUBLICA).doesNotContain(PRIVADA)
                .contains("forecast-regions").contains("401");
    }

    private void semCredencial(Throwable e) {
        assertThat(e.getMessage()).doesNotContain(PUBLICA).doesNotContain(PRIVADA);
        assertThat(e.toString()).doesNotContain(PUBLICA).doesNotContain(PRIVADA);
        assertThat(e.getCause()).isNull();
    }

    private void assertMotivo(Motivo motivo, Integer status) {
        assertThatThrownBy(() -> cliente().listarRegioes())
                .isInstanceOfSatisfying(NimbusIndisponivelException.class, e -> {
                    assertThat(e.motivo()).isEqualTo(motivo);
                    assertThat(e.status()).isEqualTo(status);
                });
    }
}

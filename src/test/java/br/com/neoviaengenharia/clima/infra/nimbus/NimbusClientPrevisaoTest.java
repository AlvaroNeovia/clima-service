package br.com.neoviaengenharia.clima.infra.nimbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.neoviaengenharia.clima.domain.previsao.PontoPrevisao;
import br.com.neoviaengenharia.clima.infra.nimbus.NimbusIndisponivelException.Motivo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class NimbusClientPrevisaoTest {

    private static final String PUBLICA = "chave-publica-teste";
    private static final String PRIVADA = "chave-privada-teste";
    private static final Instant INICIO = Instant.parse("2026-10-15T00:00:00Z");
    private static final Instant FIM = Instant.parse("2026-10-30T00:00:00Z");
    /** Exemplo da Documentação.md §3 (segundo ponto com fog_fsi/fog_vis nulos, como diz o texto). */
    private static final String JSON_DOC = """
            [{"region_id":1359,"forecasts":[
              {"datetime":1790780400000,"run_datetime":1790726400000,"wind_direction":297,"temperature":35,
               "wind_gust":18.14,"wind_speed":10.8,"relative_humidity":24.19,"precipitation":0.2,"fog_fsi":68.63,"fog_vis":5},
              {"datetime":1790784000000,"run_datetime":1790726400000,"wind_direction":297,"temperature":34.66,
               "wind_gust":19.4,"wind_speed":15.48,"relative_humidity":23.53,"precipitation":0.19,"fog_fsi":null,"fog_vis":null}
            ]}]
            """;

    private final ObjectMapper mapper = new ObjectMapper();
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
        return new NimbusClient(NimbusConfig.criarRestClient(props), mapper);
    }

    private NimbusClient cliente() {
        return cliente(Duration.ofSeconds(5));
    }

    private void responder(int status, String corpo) {
        server.enqueue(new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(corpo));
    }

    private Map<Long, List<PontoPrevisao>> buscar(Long... ids) {
        return cliente().buscarPrevisoes(List.of(ids), INICIO, FIM);
    }

    @Test
    void enviaCorpoEHeaders() throws Exception {
        responder(200, "[]");
        cliente().buscarPrevisoes(List.of(1706L, 1710L), INICIO, FIM);

        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/retrieve-forecasts");
        assertThat(req.getHeader("api_key")).isEqualTo(PUBLICA);
        assertThat(req.getHeader("authorization")).isEqualTo(PRIVADA);
        assertThat(mapper.readTree(req.getBody().readUtf8())).isEqualTo(mapper.readTree(
                "{\"regions\":[{\"region_id\":1706},{\"region_id\":1710}],\"datetime_start\":1792022400000,"
                        + "\"datetime_end\":1793318400000,\"frequency\":3600000,\"integration\":true}"));
    }

    @Test
    void mapeiaPontosDaDoc() {
        responder(200, JSON_DOC);

        List<PontoPrevisao> pontos = buscar(1359L).get(1359L);

        assertThat(pontos).hasSize(2);
        PontoPrevisao p = pontos.get(0);
        assertThat(p.dataHora()).isEqualTo(Instant.ofEpochMilli(1790780400000L));
        assertThat(p.rodadaPonto()).isEqualTo(Instant.ofEpochMilli(1790726400000L));
        assertThat(p.variaveis()).containsOnlyKeys("wind_direction", "temperature", "wind_gust", "wind_speed",
                "relative_humidity", "precipitation", "fog_fsi", "fog_vis");
        assertThat(p.variaveis()).containsEntry("temperature", 35).containsEntry("wind_gust", 18.14);
    }

    @Test
    void variavelNulaPreservada() {
        responder(200, JSON_DOC);

        assertThat(buscar(1359L).get(1359L).get(1).variaveis())
                .containsEntry("fog_fsi", null).containsEntry("fog_vis", null);
    }

    @Test
    void variasRegioesEForecastsVazio() {
        responder(200, "[" + JSON_DOC.strip().substring(1, JSON_DOC.strip().length() - 1)
                + ",{\"region_id\":1706,\"forecasts\":[]}]");

        Map<Long, List<PontoPrevisao>> r = buscar(1359L, 1706L);

        assertThat(r).containsOnlyKeys(1359L, 1706L);
        assertThat(r.get(1359L)).hasSize(2);
        assertThat(r.get(1706L)).isEmpty();
    }

    @Test
    void regiaoOmitida() {
        responder(200, JSON_DOC);

        assertThat(buscar(1359L, 1706L)).containsOnlyKeys(1359L);
    }

    @Test
    void regiaoNaoPedidaEhIgnorada() {
        responder(200, "[{\"region_id\":9999,\"forecasts\":[]}]");

        assertThat(buscar(1359L)).isEmpty();
    }

    @Test
    void pontoSemRunDatetimeEhCorpoInvalido() {
        responder(200, "[{\"region_id\":1359,\"forecasts\":[{\"datetime\":1790780400000,\"temperature\":35}]}]");

        assertThatThrownBy(() -> buscar(1359L)).isInstanceOf(NimbusIndisponivelException.class)
                .extracting("motivo").isEqualTo(Motivo.CORPO_INVALIDO);
    }

    @Test
    void pontoSemDatetimeEhCorpoInvalido() {
        responder(200, "[{\"region_id\":1359,\"forecasts\":[{\"run_datetime\":1790726400000}]}]");

        assertThatThrownBy(() -> buscar(1359L)).extracting("motivo").isEqualTo(Motivo.CORPO_INVALIDO);
    }

    @Test
    void forecastsAusenteEhCorpoInvalido() {
        responder(200, "[{\"region_id\":1359}]");

        assertThatThrownBy(() -> buscar(1359L)).extracting("motivo").isEqualTo(Motivo.CORPO_INVALIDO);
    }

    @Test
    void corpoNaoArrayEhCorpoInvalido() {
        responder(200, "{}");

        assertThatThrownBy(() -> buscar(1359L)).extracting("motivo").isEqualTo(Motivo.CORPO_INVALIDO);
    }

    @Test
    void corpoIlegivelEhCorpoInvalido() {
        responder(200, "não é json");

        assertThatThrownBy(() -> buscar(1359L)).extracting("motivo").isEqualTo(Motivo.CORPO_INVALIDO);
    }

    @Test
    void status401() {
        responder(401, "{}");

        assertThatThrownBy(() -> buscar(1359L)).isInstanceOfSatisfying(NimbusIndisponivelException.class, e -> {
            assertThat(e.motivo()).isEqualTo(Motivo.CREDENCIAL_RECUSADA);
            assertThat(e.status()).isEqualTo(401);
        });
    }

    @Test
    void status403() {
        responder(403, "{}");

        assertThatThrownBy(() -> buscar(1359L)).extracting("motivo").isEqualTo(Motivo.CREDENCIAL_RECUSADA);
    }

    @Test
    void status503() {
        responder(503, "{}");

        assertThatThrownBy(() -> buscar(1359L)).extracting("motivo").isEqualTo(Motivo.ERRO_HTTP);
    }

    @Test
    void timeout() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("[]")
                .setHeadersDelay(1, TimeUnit.SECONDS));

        assertThatThrownBy(() -> cliente(Duration.ofMillis(200)).buscarPrevisoes(List.of(1359L), INICIO, FIM))
                .extracting("motivo").isEqualTo(Motivo.TIMEOUT);
    }

    @Test
    void logRegioesSemCredencial(CapturedOutput saida) {
        responder(200, JSON_DOC);
        buscar(1359L);

        assertThat(saida.getAll()).contains("retrieve-forecasts").contains("regioes=[1359]").contains("status=200");
        assertThat(saida.getAll()).doesNotContain(PUBLICA).doesNotContain(PRIVADA);
    }
}

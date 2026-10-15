package br.com.neoviaengenharia.clima.ui.rest;

import static br.com.neoviaengenharia.clima.app.observado.ObservadoFixture.ATIVA;
import static br.com.neoviaengenharia.clima.app.observado.ObservadoFixture.horas;
import static br.com.neoviaengenharia.clima.app.observado.ObservadoFixture.observado;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.app.observado.ObservadoFixture;
import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PrecipitacaoObservadaApiIT extends IntegracaoBase {

    static final Instant AGORA = Instant.parse("2026-10-15T18:00:00Z");
    static final Instant DIA_14 = Instant.parse("2026-10-14T00:00:00Z");

    @Autowired
    ApplicationContext ctx;

    @Autowired
    TestRestTemplate http;

    ObservadoFixture fx;

    @BeforeEach
    void preparar() {
        relogio.definir(AGORA);
        fx = new ObservadoFixture(ctx);
        fx.limpar(nimbus);
        fx.catalogo(AGORA);
    }

    @AfterEach
    void limpar() {
        fx.limpar(nimbus);
    }

    private static String url(String inicio, String fim) {
        return "/api/clima/precipitacoes-observadas/1674?inicio=" + inicio + "&fim=" + fim;
    }

    @Test
    void pontaAPonta() {
        nimbus.enqueue(observado(ATIVA, horas(DIA_14, 24), "0"));
        int antes = nimbus.getRequestCount();

        ResponseEntity<String> r = http.getForEntity(url("2026-10-14T00:00:00Z", "2026-10-15T00:00:00Z"), String.class);
        ResponseEntity<String> denovo = http.getForEntity(url("2026-10-14T00:00:00Z", "2026-10-15T00:00:00Z"), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"regionId\":1674", "\"desatualizado\":false",
                "\"dataHora\":\"2026-10-14T00:00:00Z\"", "\"dataHora\":\"2026-10-14T23:00:00Z\"",
                "\"obtidoEm\":\"2026-10-15T18:00:00Z\"");
        assertThat(r.getBody().split("\"dataHora\"", -1)).hasSize(25);
        assertThat(denovo.getBody()).isEqualTo(r.getBody());
        assertThat(nimbus.getRequestCount() - antes).isEqualTo(1);
    }

    @Test
    void inexistente404() {
        ResponseEntity<String> r = http.getForEntity(
                "/api/clima/precipitacoes-observadas/99999?inicio=2026-10-14T00:00:00Z&fim=2026-10-15T00:00:00Z",
                String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).contains("Região não encontrada");
    }

    @Test
    void apiDocsTemEndpoint() {
        assertThat(http.getForObject("/api-docs", String.class))
                .contains("/api/clima/precipitacoes-observadas/{regionId}");
    }

    @Test
    void p95ComCacheQuente() {
        Instant inicio = Instant.parse("2026-10-07T00:00:00Z");
        fx.gravar(ATIVA, horas(inicio, 168), "0.2", AGORA);
        String url = url("2026-10-07T00:00:00Z", "2026-10-14T00:00:00Z");
        assertThat(http.getForEntity(url, String.class).getStatusCode()).isEqualTo(HttpStatus.OK); // aquece

        List<Long> duracoesMs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long t0 = System.nanoTime();
            ResponseEntity<String> r = http.getForEntity(url, String.class);
            duracoesMs.add((System.nanoTime() - t0) / 1_000_000);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        Collections.sort(duracoesMs);
        long p95 = duracoesMs.get(94);
        System.out.println("p95 observado 7 dias, cache quente: " + p95 + " ms");
        assertThat(p95).isLessThan(200);
    }
}

package br.com.neoviaengenharia.clima;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

class HealthIT extends IntegracaoBase {

    @Autowired
    TestRestTemplate http;

    @Test
    void healthUp() {
        ResponseEntity<String> resposta = http.getForEntity("/actuator/health", String.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody()).contains("\"status\":\"UP\"");
    }
}

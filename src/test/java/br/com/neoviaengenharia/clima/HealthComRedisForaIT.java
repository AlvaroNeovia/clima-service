package br.com.neoviaengenharia.clima;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.support.IntegracaoRedisForaBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

class HealthComRedisForaIT extends IntegracaoRedisForaBase {

    @Autowired
    TestRestTemplate http;

    @Test
    void healthUpComRedisFora() {
        ResponseEntity<String> resposta = http.getForEntity("/actuator/health", String.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody()).contains("\"status\":\"UP\"");
    }
}

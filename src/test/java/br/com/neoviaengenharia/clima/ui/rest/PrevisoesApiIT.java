package br.com.neoviaengenharia.clima.ui.rest;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.domain.regiao.Regiao;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoNimbus;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogo;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogoRepository;
import br.com.neoviaengenharia.clima.infra.repo.PrevisaoTabelas;
import br.com.neoviaengenharia.clima.infra.repo.RegiaoRepository;
import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Instant;
import java.util.Set;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.QueueDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class PrevisoesApiIT extends IntegracaoBase {

    private static final Instant AGORA = Instant.parse("2026-10-15T18:00:00Z");

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RegiaoRepository regioes;

    @Autowired
    BuscaCatalogoRepository buscas;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    @Qualifier("catalogoL1")
    Cache<?, ?> catalogoL1;

    @Autowired
    @Qualifier("previsaoVigenteL1")
    Cache<?, ?> previsaoL1;

    @BeforeEach
    void preparar() {
        relogio.definir(AGORA);
        limpar();
        regioes.save(Regiao.nova(new RegiaoNimbus(1359, "Franca", "f", false, 25), AGORA));
        buscas.save(new BuscaCatalogo(AGORA, 1));
    }

    @AfterEach
    void limpar() {
        nimbus.setDispatcher(new QueueDispatcher());
        PrevisaoTabelas.limparComRegioes(jdbc);
        buscas.deleteAll();
        catalogoL1.invalidateAll();
        previsaoL1.invalidateAll();
        Set<String> chaves = redis.keys("clima:*");
        if (chaves != null && !chaves.isEmpty()) {
            redis.delete(chaves);
        }
    }

    @Test
    void previsaoPontaAPonta() {
        nimbus.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("[{\"region_id\":1359,\"forecasts\":[{\"datetime\":1792083600000,\"run_datetime\":1792022400000,"
                        + "\"temperature\":35,\"fog_fsi\":null,\"pressure\":1012}]}]"));

        ResponseEntity<String> r = http.getForEntity("/api/clima/previsoes?regioes=1359", String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody())
                .contains("\"regionId\":1359", "\"rodada\":\"2026-10-15T00:00:00Z\"")
                .contains("\"dataHora\":\"2026-10-15T17:00:00Z\"")
                .contains("\"temperatura\":35", "\"nevoeiroFsi\":null", "\"pressure\":1012")
                .contains("\"desatualizado\":false", "\"erro\":null");
    }

    @Test
    void apiDocsListaOEndpoint() {
        assertThat(http.getForObject("/api-docs", String.class)).contains("/api/clima/previsoes");
    }
}

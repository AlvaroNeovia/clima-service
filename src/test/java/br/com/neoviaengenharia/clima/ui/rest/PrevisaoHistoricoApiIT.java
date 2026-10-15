package br.com.neoviaengenharia.clima.ui.rest;

import static br.com.neoviaengenharia.clima.domain.previsao.PontosTeste.ponto;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.domain.previsao.PontoPrevisao;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaPrevisao;
import br.com.neoviaengenharia.clima.domain.regiao.Regiao;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoNimbus;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogo;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogoRepository;
import br.com.neoviaengenharia.clima.infra.repo.PrevisaoTabelas;
import br.com.neoviaengenharia.clima.infra.repo.RegiaoRepository;
import br.com.neoviaengenharia.clima.infra.repo.RodadaPrevisaoRepositorio;
import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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

class PrevisaoHistoricoApiIT extends IntegracaoBase {

    private static final Instant AGORA = Instant.parse("2026-10-15T18:00:00Z");
    private static final String R14 = "2026-10-14T00:00:00Z";
    private static final String R15 = "2026-10-15T00:00:00Z";

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RegiaoRepository regioes;

    @Autowired
    BuscaCatalogoRepository buscas;

    @Autowired
    RodadaPrevisaoRepositorio rodadas;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    @Qualifier("catalogoL1")
    Cache<?, ?> catalogoL1;

    @Autowired
    @Qualifier("previsaoVigenteL1")
    Cache<?, ?> previsaoVigenteL1;

    @Autowired
    @Qualifier("previsaoRodadaL1")
    Cache<?, ?> previsaoRodadaL1;

    int requisicoesAntes;

    @BeforeEach
    void preparar() throws InterruptedException {
        relogio.definir(AGORA);
        nimbus.setDispatcher(new QueueDispatcher());
        while (nimbus.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS) != null) {
            // descarta requisições de testes anteriores
        }
        limpar();
        regioes.save(Regiao.nova(new RegiaoNimbus(1359, "Franca", "f", false, 25), AGORA));
        buscas.save(new BuscaCatalogo(AGORA, 1));
        rodadas.gravar(rodada(R14, "2026-10-14T02:58:10Z", ponto("2026-10-14T00:00:00Z", R14, "temperature", 22.1,
                "precipitation", 0)));
        rodadas.gravar(rodada(R15, "2026-10-15T03:12:44Z", ponto("2026-10-15T00:00:00Z", R15, "temperature", 35)));
        requisicoesAntes = nimbus.getRequestCount();
    }

    @AfterEach
    void limpar() {
        PrevisaoTabelas.limparComRegioes(jdbc);
        buscas.deleteAll();
        catalogoL1.invalidateAll();
        previsaoVigenteL1.invalidateAll();
        previsaoRodadaL1.invalidateAll();
        Set<String> chaves = redis.keys("clima:*");
        if (chaves != null && !chaves.isEmpty()) {
            redis.delete(chaves);
        }
    }

    private static RodadaPrevisao rodada(String run, String obtidoEm, PontoPrevisao... pontos) {
        return new RodadaPrevisao(1359, Instant.parse(run), Instant.parse(obtidoEm), List.of(pontos));
    }

    @Test
    void listagemPontaAPonta() {
        ResponseEntity<String> r = http.getForEntity(
                "/api/clima/previsoes/1359/rodadas?de=2026-10-01T00:00:00Z&ate=2026-10-31T00:00:00Z", String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"regionId\":1359")
                .contains("\"rodada\":\"" + R15 + "\"", "\"obtidoEm\":\"2026-10-15T03:12:44Z\"")
                .contains("\"rodada\":\"" + R14 + "\"");
        assertThat(r.getBody().indexOf(R15)).isLessThan(r.getBody().indexOf(R14)); // mais recente primeiro
        assertThat(nimbus.getRequestCount()).isEqualTo(requisicoesAntes);
    }

    @Test
    void rodadaPontaAPonta() {
        ResponseEntity<String> r = http.getForEntity("/api/clima/previsoes/1359/rodadas/" + R14, String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"regionId\":1359", "\"rodada\":\"" + R14 + "\"",
                "\"obtidoEm\":\"2026-10-14T02:58:10Z\"", "\"temperatura\":22.1", "\"precipitacao\":0",
                "\"desatualizado\":false", "\"erro\":null");
        assertThat(nimbus.getRequestCount()).isEqualTo(requisicoesAntes);
    }

    @Test
    void rodadaComOffsetEMilissegundos() {
        assertThat(http.getForEntity("/api/clima/previsoes/1359/rodadas/2026-10-14T00:00:00.000Z", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/clima/previsoes/1359/rodadas/2026-10-13T21:00:00-03:00", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(nimbus.getRequestCount()).isEqualTo(requisicoesAntes);
    }

    @Test
    void regiaoInexistente404() {
        ResponseEntity<String> r = http.getForEntity(
                "/api/clima/previsoes/99999/rodadas?de=2026-10-01T00:00:00Z&ate=2026-10-31T00:00:00Z", String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).contains("\"message\":\"Região não encontrada\"");
    }

    @Test
    void apiDocsListaOsEndpoints() {
        assertThat(http.getForObject("/api-docs", String.class))
                .contains("/api/clima/previsoes/{regionId}/rodadas", "/api/clima/previsoes/{regionId}/rodadas/{rodada}");
    }
}
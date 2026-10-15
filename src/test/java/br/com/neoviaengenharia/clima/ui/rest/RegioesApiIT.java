package br.com.neoviaengenharia.clima.ui.rest;

import static br.com.neoviaengenharia.clima.app.regiao.CatalogoFixture.ativa;
import static br.com.neoviaengenharia.clima.app.regiao.CatalogoFixture.resposta;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.app.regiao.CatalogoFixture;
import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import br.com.neoviaengenharia.clima.support.RelogioMutavel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class RegioesApiIT extends IntegracaoBase {

    @Autowired
    ApplicationContext ctx;

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void preparar() {
        relogio.definir(RelogioMutavel.INSTANTE_PADRAO);
        new CatalogoFixture(ctx).limpar(nimbus);
    }

    @Test
    void getRegioesPontaAPonta() {
        nimbus.enqueue(resposta(ativa(1359), ativa(1706)));

        ResponseEntity<String> r = http.getForEntity("/api/clima/regioes", String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody())
                .contains("\"regionId\":1359", "\"regionId\":1706")
                .contains("\"obtidoEm\":\"2026-10-15T12:00:00Z\"")
                .contains("\"desatualizado\":false");
    }

    @Test
    void apiDocsListaOsEndpoints() {
        String docs = http.getForObject("/api-docs", String.class);

        assertThat(docs).contains("/api/clima/regioes", "/api/clima/regioes/atualizar");
    }
}

package br.com.neoviaengenharia.clima.app.previsao;

import static br.com.neoviaengenharia.clima.app.previsao.PrevisaoFixture.ativa;
import static br.com.neoviaengenharia.clima.domain.previsao.PontosTeste.ponto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.neoviaengenharia.clima.support.IntegracaoRedisForaBase;
import br.com.neoviaengenharia.clima.support.RelogioMutavel;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class PrevisaoHistoricoServiceRedisForaIT extends IntegracaoRedisForaBase {

    private static final String R14 = "2026-10-14T00:00:00Z";
    private static final String R15 = "2026-10-15T00:00:00Z";

    @Autowired
    PrevisaoHistoricoService service;

    @Autowired
    ApplicationContext ctx;

    PrevisaoFixture fx;

    @BeforeEach
    void preparar() throws InterruptedException {
        relogio.definir(RelogioMutavel.INSTANTE_PADRAO);
        fx = new PrevisaoFixture(ctx);
        fx.limpar(nimbus);
        fx.semearCatalogo(RelogioMutavel.INSTANTE_PADRAO, ativa(1359));
        fx.gravarRodada(1359, R14, "2026-10-14T03:00:00Z", ponto("2026-10-14T15:00:00Z", R14, "temperature", 30));
    }

    @AfterEach
    void limparDepois() throws InterruptedException {
        fx.limpar(nimbus);
    }

    @Test
    void redisForaServeDoSql() {
        assertThat(service.listar(1359, Instant.parse(R14), Instant.parse(R15)))
                .singleElement()
                .satisfies(r -> assertThat(r.rodada()).isEqualTo(Instant.parse(R14)));

        assertThat(service.rodada(1359, Instant.parse(R14), null, null).rodada().pontos()).hasSize(1);
        assertThat(service.rodada(1359, Instant.parse(R14), null, null).rodada().pontos()).hasSize(1); // segunda vem do L1
        assertThat(nimbus.getRequestCount()).isZero();
    }

    @Test
    void regiaoInexistenteComRedisFora() {
        assertThatThrownBy(() -> service.listar(99999, Instant.parse(R14), Instant.parse(R15)))
                .isInstanceOf(RegiaoNaoEncontradaException.class);
    }
}
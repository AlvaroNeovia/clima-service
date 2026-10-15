package br.com.neoviaengenharia.clima.app.observado;

import static br.com.neoviaengenharia.clima.app.observado.ObservadoFixture.ATIVA;
import static br.com.neoviaengenharia.clima.app.observado.ObservadoFixture.horas;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.domain.observado.JanelaObservada;
import br.com.neoviaengenharia.clima.support.IntegracaoRedisForaBase;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
class PrecipitacaoObservadaServiceRedisForaIT extends IntegracaoRedisForaBase {

    static final Instant AGORA = Instant.parse("2026-10-15T18:00:00Z");
    static final Instant DIA_14 = Instant.parse("2026-10-14T00:00:00Z");

    @Autowired
    PrecipitacaoObservadaService service;

    @Autowired
    ApplicationContext ctx;

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

    @Test
    void redisForaServeDoSql(CapturedOutput saida) {
        fx.gravar(ATIVA, horas(DIA_14, 24), "0", AGORA);
        int antes = nimbus.getRequestCount();

        ObservadoResultado r = service.consultar(ATIVA, new JanelaObservada(DIA_14, DIA_14.plusSeconds(86_400)));

        assertThat(r.horas()).hasSize(24);
        assertThat(r.desatualizado()).isFalse();
        assertThat(nimbus.getRequestCount() - antes).isZero();
        assertThat(saida.getAll()).contains("Falha no cache L2");
    }
}

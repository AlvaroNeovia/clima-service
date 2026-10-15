package br.com.neoviaengenharia.clima.app.regiao;

import static br.com.neoviaengenharia.clima.app.regiao.CatalogoFixture.ativa;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.domain.regiao.RegiaoResumo;
import br.com.neoviaengenharia.clima.support.IntegracaoRedisForaBase;
import br.com.neoviaengenharia.clima.support.RelogioMutavel;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

/** S1-AC-11 num contexto com o Redis apontando para uma porta fechada. */
@ExtendWith(OutputCaptureExtension.class)
class CatalogoRegioesServiceRedisForaIT extends IntegracaoRedisForaBase {

    @Autowired
    CatalogoRegioesService service;

    @Autowired
    ApplicationContext ctx;

    CatalogoFixture fx;

    @BeforeEach
    void preparar() {
        relogio.definir(RelogioMutavel.INSTANTE_PADRAO);
        fx = new CatalogoFixture(ctx);
        fx.limpar(nimbus);
    }

    @Test
    void redisForaServeDoSql(CapturedOutput saida) {
        Instant duasHorasAtras = relogio.instant().minus(Duration.ofHours(2));
        fx.gravar(duasHorasAtras, ativa(1359), ativa(1706));
        int antes = nimbus.getRequestCount();

        CatalogoResultado r = service.obter();

        assertThat(r.desatualizado()).isFalse();
        assertThat(r.snapshot().obtidoEm()).isEqualTo(duasHorasAtras);
        assertThat(r.snapshot().regioes()).extracting(RegiaoResumo::regionId).containsExactly(1359L, 1706L);
        assertThat(nimbus.getRequestCount()).isEqualTo(antes);
        assertThat(saida.getAll()).contains("WARN").contains("Falha no cache L2");
    }
}

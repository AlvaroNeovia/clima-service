package br.com.neoviaengenharia.clima.app.regiao;

import static br.com.neoviaengenharia.clima.app.regiao.CatalogoFixture.ativa;
import static br.com.neoviaengenharia.clima.app.regiao.CatalogoFixture.inativa;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import br.com.neoviaengenharia.clima.support.RelogioMutavel;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class ValidacaoRegiaoIT extends IntegracaoBase {

    @Autowired
    ValidacaoRegiao validacao;

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
    void verificaAtivaInativaRemovidaInexistente() {
        Instant umaHoraAtras = relogio.instant().minus(Duration.ofHours(1));
        fx.gravar(umaHoraAtras, ativa(1359), inativa(1706));
        fx.gravarRemovida(umaHoraAtras, ativa(1800));
        int antes = nimbus.getRequestCount();

        assertThat(validacao.verificar(1359)).isEmpty();
        assertThat(validacao.verificar(1706)).contains(MotivoRecusa.INATIVA);
        assertThat(validacao.verificar(1800)).contains(MotivoRecusa.NAO_ENCONTRADA);
        assertThat(validacao.verificar(99999)).contains(MotivoRecusa.NAO_ENCONTRADA);

        assertThat(nimbus.getRequestCount()).isEqualTo(antes);
    }
}

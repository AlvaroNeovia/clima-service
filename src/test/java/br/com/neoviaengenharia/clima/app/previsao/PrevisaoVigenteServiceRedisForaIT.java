package br.com.neoviaengenharia.clima.app.previsao;

import static br.com.neoviaengenharia.clima.app.previsao.PrevisaoFixture.ativa;
import static br.com.neoviaengenharia.clima.domain.previsao.PontosTeste.ponto;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.support.IntegracaoRedisForaBase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
class PrevisaoVigenteServiceRedisForaIT extends IntegracaoRedisForaBase {

    private static final Instant AGORA = Instant.parse("2026-10-15T18:00:00Z");
    private static final String R15 = "2026-10-15T00:00:00Z";

    @Autowired
    PrevisaoVigenteService service;

    @Autowired
    ApplicationContext ctx;

    PrevisaoFixture fx;

    @BeforeEach
    void preparar() throws InterruptedException {
        relogio.definir(AGORA);
        fx = new PrevisaoFixture(ctx);
        fx.limpar(nimbus);
        fx.semearCatalogo(AGORA, ativa(1359));
    }

    @AfterEach
    void limparDepois() throws InterruptedException {
        fx.limpar(nimbus);
    }

    @Test
    void redisForaServeDoSql(CapturedOutput saida) {
        fx.gravarRodada(1359, R15, R15, ponto("2026-10-15T15:00:00Z", R15, "temperature", 35));
        int antes = nimbus.getRequestCount();

        ItemPrevisao item = service.consultar(List.of(1359L), null, null).itens().get(0);

        assertThat(item.erro()).isNull();
        assertThat(item.desatualizado()).isFalse();
        assertThat(item.rodada().pontos()).hasSize(1);
        assertThat(nimbus.getRequestCount()).isEqualTo(antes);
        assertThat(saida.getAll()).contains("WARN").contains("Falha no cache L2");
    }
}

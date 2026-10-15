package br.com.neoviaengenharia.clima.infra.cache;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.domain.observado.EstadoHora;
import br.com.neoviaengenharia.clima.support.IntegracaoRedisForaBase;
import br.com.neoviaengenharia.clima.support.RelogioMutavel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ObservadoCacheRedisForaIT extends IntegracaoRedisForaBase {

    @Autowired
    ObservadoCache cache;

    @BeforeEach
    void limpar() {
        relogio.definir(RelogioMutavel.INSTANTE_PADRAO);
        cache.limparL1();
    }

    @Test
    void redisForaNaoLancaELoga(CapturedOutput saida) {
        Instant h = Instant.parse("2026-10-14T00:00:00Z");
        EstadoHora semDado = new EstadoHora(h, null, null, true, h);

        cache.guardar(1674, List.of(semDado));
        assertThat(cache.obter(1674, List.of(h))).containsEntry(h, semDado);
        cache.limparL1();
        assertThat(cache.obter(1674, List.of(h))).isEmpty();

        assertThat(saida.getAll()).contains("Falha no cache L2");
    }
}

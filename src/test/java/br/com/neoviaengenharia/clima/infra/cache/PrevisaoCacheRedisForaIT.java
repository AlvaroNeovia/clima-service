package br.com.neoviaengenharia.clima.infra.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import br.com.neoviaengenharia.clima.domain.previsao.PrevisaoVigente;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaPrevisao;
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
class PrevisaoCacheRedisForaIT extends IntegracaoRedisForaBase {

    @Autowired
    PrevisaoCache cache;

    @BeforeEach
    void limpar() {
        relogio.definir(RelogioMutavel.INSTANTE_PADRAO);
        cache.limparL1();
    }

    @Test
    void redisForaNaoLanca(CapturedOutput saida) {
        Instant rodada = Instant.parse("2026-10-15T00:00:00Z");
        PrevisaoVigente v = new PrevisaoVigente(new RodadaPrevisao(1359, rodada, rodada, List.of()),
                Instant.parse("2026-10-16T00:00:00Z"), false);

        assertThatCode(() -> cache.guardar(v)).doesNotThrowAnyException();
        assertThat(cache.obter(1359)).contains(v); // L1 segue funcionando
        cache.limparL1();
        assertThatCode(() -> cache.obter(1359)).doesNotThrowAnyException();
        assertThatCode(() -> cache.invalidarRodada(1359, rodada)).doesNotThrowAnyException();

        assertThat(saida.getAll()).contains("Falha no cache L2");
    }
}

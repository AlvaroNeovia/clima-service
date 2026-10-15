package br.com.neoviaengenharia.clima.infra.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import br.com.neoviaengenharia.clima.domain.regiao.CatalogoSnapshot;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoResumo;
import br.com.neoviaengenharia.clima.support.IntegracaoRedisForaBase;
import br.com.neoviaengenharia.clima.support.RelogioMutavel;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class CatalogoCacheRedisForaIT extends IntegracaoRedisForaBase {

    @Autowired
    CatalogoCache cache;

    @BeforeEach
    void limpar() {
        relogio.definir(RelogioMutavel.INSTANTE_PADRAO);
        cache.limparL1();
    }

    @Test
    void redisForaNaoLancaELoga(CapturedOutput saida) {
        CatalogoSnapshot s = new CatalogoSnapshot(
                List.of(new RegiaoResumo(1L, "Norte", "N", false, 10L)), relogio.instant());

        assertThatCode(() -> cache.guardar(s)).doesNotThrowAnyException();
        cache.limparL1();
        assertThatCode(() -> cache.obter()).doesNotThrowAnyException();

        assertThat(saida.getAll()).contains("Falha no cache L2");
    }
}

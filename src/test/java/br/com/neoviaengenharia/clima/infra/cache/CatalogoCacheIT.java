package br.com.neoviaengenharia.clima.infra.cache;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.domain.regiao.CatalogoSnapshot;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoResumo;
import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import br.com.neoviaengenharia.clima.support.RelogioMutavel;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class CatalogoCacheIT extends IntegracaoBase {

    @Autowired
    CatalogoCache cache;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void limpar() {
        relogio.definir(RelogioMutavel.INSTANTE_PADRAO);
        cache.limparL1();
        redis.delete("clima:regioes:catalogo");
    }

    private CatalogoSnapshot snapshot() {
        return new CatalogoSnapshot(
                List.of(new RegiaoResumo(1L, "Norte", "N", false, 10L)), relogio.instant());
    }

    @Test
    void guardaEDevolveDoL1() {
        CatalogoSnapshot s = snapshot();
        cache.guardar(s);
        assertThat(cache.obter()).contains(s);
    }

    @Test
    void promoveDoL2() {
        CatalogoSnapshot s = snapshot();
        cache.guardar(s);
        cache.limparL1();
        assertThat(cache.obter()).contains(s);
        assertThat(redis.getExpire("clima:regioes:catalogo")).isPositive();
    }

    @Test
    void naoDevolveVencido() {
        cache.guardar(snapshot());
        relogio.avancar(Duration.ofHours(24));
        assertThat(cache.obter()).isEmpty();
    }

    @Test
    void guardarSubstituiSnapshotAntigoNoL1() {
        cache.guardar(snapshot());
        relogio.avancar(Duration.ofHours(1));
        CatalogoSnapshot novo = new CatalogoSnapshot(List.of(), relogio.instant());
        cache.guardar(novo);
        assertThat(cache.obter()).contains(novo);
    }
}

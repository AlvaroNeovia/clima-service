package br.com.neoviaengenharia.clima.infra.cache;

import static br.com.neoviaengenharia.clima.domain.previsao.PontosTeste.ponto;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.domain.previsao.PrevisaoVigente;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaPrevisao;
import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class PrevisaoCacheIT extends IntegracaoBase {

    private static final String R15 = "2026-10-15T00:00:00Z";
    private static final Instant MEIA_NOITE = Instant.parse("2026-10-16T00:00:00Z");

    @Autowired
    PrevisaoCache cache;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void limpar() {
        relogio.definir(Instant.parse("2026-10-15T18:00:00Z"));
        cache.limparL1();
        redis.delete(List.of("clima:previsao:vigente:1359", "clima:previsao:rodada:1359:1792022400000"));
    }

    private static PrevisaoVigente vigente(Instant validoAte) {
        RodadaPrevisao rodada = new RodadaPrevisao(1359, Instant.parse(R15), Instant.parse("2026-10-15T03:12:44Z"),
                List.of(ponto("2026-10-15T15:00:00Z", R15, "temperature", 35, "wind_gust", 18.14, "fog_fsi", null)));
        return new PrevisaoVigente(rodada, validoAte, false);
    }

    @Test
    void guardaEDevolveDoL1() {
        PrevisaoVigente v = vigente(MEIA_NOITE);
        cache.guardar(v);

        assertThat(cache.obter(1359)).contains(v);
        assertThat(cache.obter(1706)).isEmpty();
    }

    @Test
    void promoveDoL2() {
        PrevisaoVigente v = vigente(MEIA_NOITE);
        cache.guardar(v);
        cache.limparL1();

        assertThat(cache.obter(1359)).contains(v);
        redis.delete("clima:previsao:vigente:1359");
        assertThat(cache.obter(1359)).contains(v); // agora vem do L1
    }

    @Test
    void preservaNulosENumerosNoL2() {
        cache.guardar(vigente(MEIA_NOITE));
        cache.limparL1();

        Map<String, Object> variaveis = cache.obter(1359).orElseThrow().rodada().pontos().get(0).variaveis();

        assertThat(variaveis.get("temperature")).isInstanceOf(Integer.class).isEqualTo(35);
        assertThat(variaveis.get("wind_gust")).isInstanceOf(Double.class).isEqualTo(18.14);
        assertThat(variaveis).containsEntry("fog_fsi", null);
    }

    @Test
    void expiraNaViradaDoDia() {
        relogio.definir(Instant.parse("2026-10-15T23:59:59Z"));
        cache.guardar(vigente(MEIA_NOITE));
        assertThat(cache.obter(1359)).isPresent();

        relogio.avancar(Duration.ofSeconds(1));

        assertThat(cache.obter(1359)).isEmpty();
    }

    @Test
    void chaveRedis() {
        cache.guardar(vigente(MEIA_NOITE));

        assertThat(redis.hasKey("clima:previsao:vigente:1359")).isTrue();
        Long ttl = redis.getExpire("clima:previsao:vigente:1359", TimeUnit.SECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(Duration.ofHours(6).toSeconds());
    }

    @Test
    void naoGuardaVencido() {
        cache.guardar(vigente(Instant.parse("2026-10-15T17:00:00Z")));

        assertThat(redis.hasKey("clima:previsao:vigente:1359")).isFalse();
        assertThat(cache.obter(1359)).isEmpty();
    }

    @Test
    void invalidaChaveDaRodadaEspecifica() {
        redis.opsForValue().set("clima:previsao:rodada:1359:1792022400000", "{}");

        cache.invalidarRodada(1359, Instant.parse(R15));

        assertThat(redis.hasKey("clima:previsao:rodada:1359:1792022400000")).isFalse();
    }
}

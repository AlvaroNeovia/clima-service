package br.com.neoviaengenharia.clima.infra.cache;

import static br.com.neoviaengenharia.clima.domain.previsao.PontosTeste.ponto;
import static org.assertj.core.api.Assertions.assertThat;

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

class RodadaCacheIT extends IntegracaoBase {

    private static final String CHAVE = "clima:previsao:rodada:1359:1792022400000";
    private static final Instant RODADA = Instant.parse("2026-10-15T00:00:00Z");

    @Autowired
    PrevisaoCache cache;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void limpar() {
        relogio.definir(Instant.parse("2026-10-15T18:00:00Z"));
        cache.limparL1();
        redis.delete(List.of(CHAVE));
    }

    private static RodadaPrevisao rodada() {
        return new RodadaPrevisao(1359, RODADA, Instant.parse("2026-10-15T03:12:44Z"),
                List.of(ponto("2026-10-15T15:00:00Z", "2026-10-15T00:00:00Z", "temperature", 35, "wind_gust", 18.14,
                        "fog_fsi", null)));
    }

    @Test
    void guardaEDevolveDoL1() {
        RodadaPrevisao r = rodada();

        cache.guardarRodada(r);

        assertThat(cache.obterRodada(1359, RODADA)).contains(r);
        assertThat(cache.obterRodada(1706, RODADA)).isEmpty();
    }

    @Test
    void promoveDoL2() {
        RodadaPrevisao r = rodada();
        cache.guardarRodada(r);
        cache.limparL1();

        assertThat(cache.obterRodada(1359, RODADA)).contains(r);
        redis.delete(CHAVE);
        assertThat(cache.obterRodada(1359, RODADA)).contains(r); // agora vem do L1
    }

    @Test
    void chaveETtlNoRedis() {
        cache.guardarRodada(rodada());

        assertThat(redis.hasKey(CHAVE)).isTrue();
        Long ttl = redis.getExpire(CHAVE, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(Duration.ofHours(23).toSeconds()).isLessThanOrEqualTo(Duration.ofHours(24).toSeconds());
    }

    @Test
    void preservaNulosENumeros() {
        cache.guardarRodada(rodada());
        cache.limparL1();

        Map<String, Object> variaveis = cache.obterRodada(1359, RODADA).orElseThrow().pontos().get(0).variaveis();

        assertThat(variaveis.get("temperature")).isInstanceOf(Integer.class).isEqualTo(35);
        assertThat(variaveis.get("wind_gust")).isInstanceOf(Double.class).isEqualTo(18.14);
        assertThat(variaveis).containsEntry("fog_fsi", null);
    }

    @Test
    void invalidaApagaL1EL2() {
        cache.guardarRodada(rodada());
        cache.limparL1();

        cache.invalidarRodada(1359, RODADA);

        assertThat(redis.hasKey(CHAVE)).isFalse();
        assertThat(cache.obterRodada(1359, RODADA)).isEmpty();
    }

    @Test
    void l1ExpiraEm15Min() {
        cache.guardarRodada(rodada());
        redis.delete(CHAVE); // outra instância sobrescreveu no L2

        relogio.avancar(Duration.ofMinutes(14));
        assertThat(cache.obterRodada(1359, RODADA)).isPresent();

        relogio.avancar(Duration.ofMinutes(1));
        assertThat(cache.obterRodada(1359, RODADA)).isEmpty();
    }
}
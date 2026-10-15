package br.com.neoviaengenharia.clima.infra.cache;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.domain.observado.EstadoHora;
import br.com.neoviaengenharia.clima.domain.observado.Estacao;
import br.com.neoviaengenharia.clima.domain.observado.LeituraHora;
import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import br.com.neoviaengenharia.clima.support.RelogioMutavel;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class ObservadoCacheIT extends IntegracaoBase {

    static final Instant AGORA = RelogioMutavel.INSTANTE_PADRAO; // 2026-10-15T12:00:00Z
    static final Instant ANTIGA = Instant.parse("2026-10-14T00:00:00Z");
    static final Instant RECENTE = Instant.parse("2026-10-15T09:00:00Z");

    @Autowired
    ObservadoCache cache;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void limpar() {
        relogio.definir(AGORA);
        cache.limparL1();
        Set<String> chaves = redis.keys("clima:observado:*");
        if (chaves != null && !chaves.isEmpty()) {
            redis.delete(chaves);
        }
    }

    private static LeituraHora leitura() {
        return new LeituraHora(new Estacao(3078, "Prudentópolis", "mg_h-823"), new BigDecimal("1.5"),
                BigDecimal.ZERO, ANTIGA, null, null, false);
    }

    private static EstadoHora definitiva() {
        return new EstadoHora(ANTIGA, leitura(), ANTIGA, false, ANTIGA);
    }

    private static EstadoHora recente(Instant tentativa) {
        return new EstadoHora(RECENTE, leitura(), tentativa, false, tentativa);
    }

    @Test
    void chaveNoFormatoDaSpec() {
        assertThat(ObservadoCache.chave(1674, Instant.ofEpochMilli(1790121600000L)))
                .isEqualTo("clima:observado:1674:1790121600000");
    }

    @Test
    void guardaEDevolveDoL1() {
        cache.guardar(1674, List.of(definitiva()));
        assertThat(cache.obter(1674, List.of(ANTIGA, RECENTE))).containsOnlyKeys(ANTIGA)
                .containsEntry(ANTIGA, definitiva());
    }

    @Test
    void separaPorRegiao() {
        cache.guardar(1674, List.of(definitiva()));
        assertThat(cache.obter(1706, List.of(ANTIGA))).isEmpty();
    }

    @Test
    void promoveDoL2() {
        cache.guardar(1674, List.of(definitiva(), recente(AGORA.minus(Duration.ofMinutes(10)))));
        cache.limparL1();

        assertThat(cache.obter(1674, List.of(ANTIGA, RECENTE))).containsOnlyKeys(ANTIGA, RECENTE);
        redis.delete(redis.keys("clima:observado:*"));
        assertThat(cache.obter(1674, List.of(ANTIGA, RECENTE))).containsOnlyKeys(ANTIGA, RECENTE);
    }

    @Test
    void recenteVenceEmTentativaMaisUmaHora() {
        Instant tentativa = AGORA.minus(Duration.ofMinutes(10));
        cache.guardar(1674, List.of(recente(tentativa)));
        assertThat(redis.getExpire(ObservadoCache.chave(1674, RECENTE), TimeUnit.SECONDS)).isBetween(2990L, 3000L);

        relogio.definir(tentativa.plus(Duration.ofHours(1)));
        assertThat(cache.obter(1674, List.of(RECENTE))).isEmpty();
    }

    @Test
    void definitivaNaoVenceETemTtlDe30DiasNoRedis() {
        cache.guardar(1674, List.of(definitiva()));
        assertThat(redis.getExpire(ObservadoCache.chave(1674, ANTIGA), TimeUnit.DAYS)).isBetween(29L, 30L);

        relogio.avancar(Duration.ofDays(20));
        assertThat(cache.obter(1674, List.of(ANTIGA))).containsKey(ANTIGA);
    }

    @Test
    void naoGuardaNaoCacheavel() {
        cache.guardar(1674, List.of(EstadoHora.vazio(RECENTE), recente(AGORA.minus(Duration.ofHours(2)))));
        assertThat(cache.obter(1674, List.of(RECENTE))).isEmpty();
        assertThat(redis.hasKey(ObservadoCache.chave(1674, RECENTE))).isFalse();
    }

    @Test
    void nulosSobrevivemAoRedis() {
        cache.guardar(1674, List.of(definitiva(), new EstadoHora(RECENTE, null, null, true, AGORA)));
        cache.limparL1();

        var lidos = cache.obter(1674, List.of(ANTIGA, RECENTE));

        assertThat(lidos.get(ANTIGA)).isEqualTo(definitiva());
        assertThat(lidos.get(ANTIGA).leitura().primeiraLeitura()).isNull();
        assertThat(lidos.get(RECENTE).semDado()).isTrue();
        assertThat(lidos.get(RECENTE).leitura()).isNull();
    }
}

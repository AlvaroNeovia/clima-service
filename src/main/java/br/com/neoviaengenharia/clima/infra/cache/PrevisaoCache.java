package br.com.neoviaengenharia.clima.infra.cache;

import br.com.neoviaengenharia.clima.domain.previsao.PrevisaoVigente;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Cache da rodada vigente por região: Caffeine (L1) e Redis (L2). Falhas do Redis não propagam. */
@Component
public class PrevisaoCache {

    private static final String PREFIXO_VIGENTE = "clima:previsao:vigente:";
    private static final String PREFIXO_RODADA = "clima:previsao:rodada:";
    private static final Logger log = LoggerFactory.getLogger(PrevisaoCache.class);

    private final Cache<Long, PrevisaoVigente> l1;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PrevisaoCache(
            Cache<Long, PrevisaoVigente> l1, StringRedisTemplate redis, ObjectMapper mapper, Clock clock) {
        this.l1 = l1;
        this.redis = redis;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Optional<PrevisaoVigente> obter(long regionId) {
        PrevisaoVigente doL1 = l1.getIfPresent(regionId);
        if (doL1 != null && doL1.valida(clock.instant())) {
            return Optional.of(doL1);
        }
        try {
            String json = redis.opsForValue().get(PREFIXO_VIGENTE + regionId);
            if (json != null) {
                PrevisaoVigente doL2 = mapper.readValue(json, PrevisaoVigente.class);
                if (doL2.valida(clock.instant())) {
                    l1.put(regionId, doL2);
                    return Optional.of(doL2);
                }
            }
        } catch (Exception e) {
            log.warn("Falha no cache L2 (Redis): {}", e.toString());
        }
        return Optional.empty();
    }

    public void guardar(PrevisaoVigente previsao) {
        Duration ttl = Duration.between(clock.instant(), previsao.validoAte());
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        long regionId = previsao.rodada().regionId();
        l1.put(regionId, previsao);
        try {
            redis.opsForValue().set(PREFIXO_VIGENTE + regionId, mapper.writeValueAsString(previsao), ttl);
        } catch (Exception e) {
            log.warn("Falha no cache L2 (Redis): {}", e.toString());
        }
    }

    /** Apaga do L2 a entrada da rodada específica (S3), depois que o S2 a sobrescreve. */
    public void invalidarRodada(long regionId, Instant rodada) {
        try {
            redis.delete(PREFIXO_RODADA + regionId + ":" + rodada.toEpochMilli());
        } catch (Exception e) {
            log.warn("Falha no cache L2 (Redis): {}", e.toString());
        }
    }

    void limparL1() {
        l1.invalidateAll();
    }
}

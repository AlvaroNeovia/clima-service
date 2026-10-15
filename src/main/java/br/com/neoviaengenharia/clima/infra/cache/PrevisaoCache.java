package br.com.neoviaengenharia.clima.infra.cache;

import br.com.neoviaengenharia.clima.domain.previsao.PrevisaoVigente;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaPrevisao;
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

/**
 * Cache da rodada vigente por região (S2) e da rodada específica (S3): Caffeine (L1) e Redis (L2). Falhas do Redis
 * não propagam.
 */
@Component
public class PrevisaoCache {

    private static final String PREFIXO_VIGENTE = "clima:previsao:vigente:";
    private static final String PREFIXO_RODADA = "clima:previsao:rodada:";
    /** TTL do L2 da rodada específica (E2): o histórico continua no SQL, o Redis não acumula. */
    private static final Duration TTL_L2_RODADA = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(PrevisaoCache.class);

    private final Cache<Long, PrevisaoVigente> l1;
    private final Cache<String, RodadaPrevisao> l1Rodada;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PrevisaoCache(
            Cache<Long, PrevisaoVigente> l1,
            Cache<String, RodadaPrevisao> l1Rodada,
            StringRedisTemplate redis,
            ObjectMapper mapper,
            Clock clock) {
        this.l1 = l1;
        this.l1Rodada = l1Rodada;
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

    /** Rodada específica (S3): L1, depois L2 com promoção ao L1. */
    public Optional<RodadaPrevisao> obterRodada(long regionId, Instant rodada) {
        String chave = chaveRodada(regionId, rodada);
        RodadaPrevisao doL1 = l1Rodada.getIfPresent(chave);
        if (doL1 != null) {
            return Optional.of(doL1);
        }
        try {
            String json = redis.opsForValue().get(chave);
            if (json != null) {
                RodadaPrevisao doL2 = mapper.readValue(json, RodadaPrevisao.class);
                l1Rodada.put(chave, doL2);
                return Optional.of(doL2);
            }
        } catch (Exception e) {
            log.warn("Falha no cache L2 (Redis): {}", e.toString());
        }
        return Optional.empty();
    }

    public void guardarRodada(RodadaPrevisao rodada) {
        String chave = chaveRodada(rodada.regionId(), rodada.rodada());
        l1Rodada.put(chave, rodada);
        try {
            redis.opsForValue().set(chave, mapper.writeValueAsString(rodada), TTL_L2_RODADA);
        } catch (Exception e) {
            log.warn("Falha no cache L2 (Redis): {}", e.toString());
        }
    }

    /** Apaga a entrada da rodada específica (S3), depois que o S2 a sobrescreve. */
    public void invalidarRodada(long regionId, Instant rodada) {
        l1Rodada.invalidate(chaveRodada(regionId, rodada));
        try {
            redis.delete(chaveRodada(regionId, rodada));
        } catch (Exception e) {
            log.warn("Falha no cache L2 (Redis): {}", e.toString());
        }
    }

    private static String chaveRodada(long regionId, Instant rodada) {
        return PREFIXO_RODADA + regionId + ":" + rodada.toEpochMilli();
    }

    void limparL1() {
        l1.invalidateAll();
        l1Rodada.invalidateAll();
    }
}

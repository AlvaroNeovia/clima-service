package br.com.neoviaengenharia.clima.infra.cache;

import br.com.neoviaengenharia.clima.domain.regiao.CatalogoSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Cache do catálogo em dois níveis: Caffeine (L1) e Redis (L2). Falhas do Redis não propagam. */
@Component
public class CatalogoCache {

    static final String CHAVE = "clima:regioes:catalogo";
    private static final Logger log = LoggerFactory.getLogger(CatalogoCache.class);

    private final Cache<String, CatalogoSnapshot> l1;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CatalogoCache(
            Cache<String, CatalogoSnapshot> l1, StringRedisTemplate redis, ObjectMapper mapper, Clock clock) {
        this.l1 = l1;
        this.redis = redis;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Optional<CatalogoSnapshot> obter() {
        CatalogoSnapshot doL1 = l1.getIfPresent(CHAVE);
        if (doL1 != null && doL1.vigente(clock.instant())) {
            return Optional.of(doL1);
        }
        try {
            String json = redis.opsForValue().get(CHAVE);
            if (json != null) {
                CatalogoSnapshot doL2 = mapper.readValue(json, CatalogoSnapshot.class);
                if (doL2.vigente(clock.instant())) {
                    l1.put(CHAVE, doL2);
                    return Optional.of(doL2);
                }
            }
        } catch (Exception e) {
            log.warn("Falha no cache L2 (Redis): {}", e.toString());
        }
        return Optional.empty();
    }

    public void guardar(CatalogoSnapshot snapshot) {
        l1.put(CHAVE, snapshot);
        try {
            Duration ttl = Duration.between(clock.instant(), snapshot.validoAte());
            if (ttl.isNegative() || ttl.isZero()) {
                return;
            }
            redis.opsForValue().set(CHAVE, mapper.writeValueAsString(snapshot), ttl);
        } catch (Exception e) {
            log.warn("Falha no cache L2 (Redis): {}", e.toString());
        }
    }

    void limparL1() {
        l1.invalidateAll();
    }
}

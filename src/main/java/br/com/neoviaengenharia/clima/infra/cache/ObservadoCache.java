package br.com.neoviaengenharia.clima.infra.cache;

import br.com.neoviaengenharia.clima.domain.observado.EstadoHora;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache das horas observadas em dois níveis: Caffeine (L1) e Redis (L2), uma chave por hora (S4 §7.3).
 * Falhas do Redis não propagam (S4-FR-15).
 */
@Component
public class ObservadoCache {

    /** Retenção operacional no Redis das horas definitivas (decisão D4); são refeitas do SQL se expirarem. */
    static final Duration RETENCAO_DEFINITIVA_L2 = Duration.ofDays(30);
    private static final Logger log = LoggerFactory.getLogger(ObservadoCache.class);

    private final Cache<String, EstadoHora> l1;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ObservadoCache(
            Cache<String, EstadoHora> observadoL1, StringRedisTemplate redis, ObjectMapper mapper, Clock clock) {
        this.l1 = observadoL1;
        this.redis = redis;
        this.mapper = mapper;
        this.clock = clock;
    }

    public static String chave(long regionId, Instant dataHora) {
        return "clima:observado:" + regionId + ":" + dataHora.toEpochMilli();
    }

    /** Estados ainda válidos das horas pedidas; as ausentes do mapa são faltas (vão ao SQL). */
    public Map<Instant, EstadoHora> obter(long regionId, List<Instant> horas) {
        Instant agora = clock.instant();
        Map<Instant, EstadoHora> achados = new LinkedHashMap<>();
        List<Instant> faltas = new ArrayList<>();
        for (Instant h : horas) {
            EstadoHora e = l1.getIfPresent(chave(regionId, h));
            if (e != null && valido(e, agora)) {
                achados.put(h, e);
            } else {
                faltas.add(h);
            }
        }
        if (faltas.isEmpty()) {
            return achados;
        }
        try {
            List<String> jsons = redis.opsForValue().multiGet(faltas.stream().map(h -> chave(regionId, h)).toList());
            for (int i = 0; jsons != null && i < faltas.size(); i++) {
                if (jsons.get(i) == null) {
                    continue;
                }
                EstadoHora e = mapper.readValue(jsons.get(i), EstadoHora.class);
                if (valido(e, agora)) {
                    l1.put(chave(regionId, faltas.get(i)), e);
                    achados.put(faltas.get(i), e);
                }
            }
        } catch (Exception e) {
            log.warn("Falha no cache L2 (Redis): {}", e.toString());
        }
        return achados;
    }

    /** Guarda os estados cacheáveis no L1 e, numa ida só (pipeline), no L2. */
    public void guardar(long regionId, Collection<EstadoHora> estados) {
        Instant agora = clock.instant();
        Map<String, EstadoHora> guardar = new LinkedHashMap<>();
        for (EstadoHora e : estados) {
            if (valido(e, agora)) {
                guardar.put(chave(regionId, e.dataHora()), e);
            }
        }
        if (guardar.isEmpty()) {
            return;
        }
        l1.putAll(guardar);
        try {
            Map<String, String> jsons = new LinkedHashMap<>();
            for (var item : guardar.entrySet()) {
                jsons.put(item.getKey(), mapper.writeValueAsString(item.getValue()));
            }
            redis.executePipelined((RedisCallback<Object>) conexao -> {
                StringRedisConnection c = (StringRedisConnection) conexao;
                jsons.forEach((chave, json) -> c.setEx(chave, ttlSegundos(guardar.get(chave), agora), json));
                return null;
            });
        } catch (Exception e) {
            log.warn("Falha no cache L2 (Redis): {}", e.toString());
        }
    }

    private static boolean valido(EstadoHora e, Instant agora) {
        Optional<Instant> ate = e.validoAte(agora);
        return ate.isEmpty() || agora.isBefore(ate.get());
    }

    private static long ttlSegundos(EstadoHora e, Instant agora) {
        return e.validoAte(agora)
                .map(ate -> Math.max(1, Duration.between(agora, ate).toSeconds()))
                .orElse(RETENCAO_DEFINITIVA_L2.toSeconds());
    }

    void limparL1() {
        l1.invalidateAll();
    }
}

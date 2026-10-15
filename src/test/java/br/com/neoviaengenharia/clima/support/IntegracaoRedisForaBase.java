package br.com.neoviaengenharia.clima.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base sem container Redis: o Redis aponta para uma porta fechada (cenário "Redis fora"). */
public abstract class IntegracaoRedisForaBase extends IntegracaoBaseComum {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registrarNimbus(registry);
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> 1);
    }
}

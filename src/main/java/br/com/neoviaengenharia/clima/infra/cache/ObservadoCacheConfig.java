package br.com.neoviaengenharia.clima.infra.cache;

import br.com.neoviaengenharia.clima.domain.observado.EstadoHora;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ObservadoCacheConfig {

    static final long MAXIMO_L1 = 200_000;

    /** L1 das horas observadas: cada entrada expira em {@code validoAte} do próprio estado (definitivas, nunca). */
    @Bean
    Cache<String, EstadoHora> observadoL1(Clock clock) {
        return Caffeine.newBuilder()
                .maximumSize(MAXIMO_L1)
                .expireAfter(new Expiry<String, EstadoHora>() {
                    @Override
                    public long expireAfterCreate(String chave, EstadoHora valor, long agora) {
                        return restante(valor);
                    }

                    @Override
                    public long expireAfterUpdate(String chave, EstadoHora valor, long agora, long duracao) {
                        return restante(valor);
                    }

                    @Override
                    public long expireAfterRead(String chave, EstadoHora valor, long agora, long duracao) {
                        return duracao;
                    }

                    private long restante(EstadoHora valor) {
                        var ate = valor.validoAte(clock.instant());
                        if (ate.isEmpty()) {
                            return Long.MAX_VALUE;
                        }
                        Duration d = Duration.between(clock.instant(), ate.get());
                        return d.isNegative() ? 0 : d.toNanos();
                    }
                })
                .build();
    }
}

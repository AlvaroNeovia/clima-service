package br.com.neoviaengenharia.clima.infra.cache;

import br.com.neoviaengenharia.clima.domain.previsao.PrevisaoVigente;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaPrevisao;
import br.com.neoviaengenharia.clima.domain.regiao.CatalogoSnapshot;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CacheConfig {

    private static final Duration VALIDADE_L1_RODADA = Duration.ofMinutes(15);

    /** L1 do catálogo: uma entrada, que expira em {@code validoAte} do próprio snapshot. */
    @Bean
    Cache<String, CatalogoSnapshot> catalogoL1(Clock clock) {
        return Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfter(new Expiry<String, CatalogoSnapshot>() {
                    @Override
                    public long expireAfterCreate(String chave, CatalogoSnapshot valor, long agora) {
                        return restante(valor);
                    }

                    @Override
                    public long expireAfterUpdate(String chave, CatalogoSnapshot valor, long agora, long duracao) {
                        return restante(valor);
                    }

                    @Override
                    public long expireAfterRead(String chave, CatalogoSnapshot valor, long agora, long duracao) {
                        return duracao;
                    }

                    private long restante(CatalogoSnapshot valor) {
                        Duration d = Duration.between(clock.instant(), valor.validoAte());
                        return d.isNegative() ? 0 : d.toNanos();
                    }
                })
                .build();
    }

    /** L1 da previsão vigente: uma entrada por região, que expira em {@code validoAte} da própria entrada. */
    @Bean
    Cache<Long, PrevisaoVigente> previsaoVigenteL1(Clock clock) {
        return Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfter(new Expiry<Long, PrevisaoVigente>() {
                    @Override
                    public long expireAfterCreate(Long chave, PrevisaoVigente valor, long agora) {
                        return restante(clock, valor.validoAte());
                    }

                    @Override
                    public long expireAfterUpdate(Long chave, PrevisaoVigente valor, long agora, long duracao) {
                        return restante(clock, valor.validoAte());
                    }

                    @Override
                    public long expireAfterRead(Long chave, PrevisaoVigente valor, long agora, long duracao) {
                        return duracao;
                    }
                })
                .build();
    }

    /**
     * L1 da rodada específica (S3): a entrada não tem validade no dado, e o S2 só invalida o L1 da instância que
     * sobrescreveu (E1). Validade de 15 min limita a defasagem dos demais pods ao mesmo limite da rodada vigente.
     */
    @Bean
    Cache<String, RodadaPrevisao> previsaoRodadaL1(Clock clock) {
        return Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(VALIDADE_L1_RODADA)
                .ticker(() -> clock.millis() * 1_000_000L)
                .build();
    }

    private static long restante(Clock clock, Instant validoAte) {
        Duration d = Duration.between(clock.instant(), validoAte);
        return d.isNegative() ? 0 : d.toNanos();
    }
}

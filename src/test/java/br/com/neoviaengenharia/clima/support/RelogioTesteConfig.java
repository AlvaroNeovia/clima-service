package br.com.neoviaengenharia.clima.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class RelogioTesteConfig {

    @Bean
    @Primary
    RelogioMutavel relogioMutavel() {
        return new RelogioMutavel();
    }
}

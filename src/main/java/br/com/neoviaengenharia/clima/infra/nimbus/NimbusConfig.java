package br.com.neoviaengenharia.clima.infra.nimbus;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(NimbusProperties.class)
public class NimbusConfig {

    @Bean
    public NimbusClient nimbusClient(NimbusProperties props) {
        return new NimbusClient(criarRestClient(props));
    }

    static RestClient criarRestClient(NimbusProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.baseUrl().toString())
                .defaultHeader("api_key", props.apiKey())
                .defaultHeader("authorization", props.authorization())
                .build();
    }
}

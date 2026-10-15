package br.com.neoviaengenharia.clima.infra.nimbus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/** Configuração da Nimbus ({@code nimbus.*}). As credenciais vêm só de variáveis de ambiente. */
@Validated
@ConfigurationProperties("nimbus")
public record NimbusProperties(
        @NotNull URI baseUrl,
        @NotBlank String apiKey,
        @NotBlank String authorization,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {

    /** Evita credenciais em logs que imprimam o record. */
    @Override
    public String toString() {
        return "NimbusProperties[baseUrl=" + baseUrl + "]";
    }
}

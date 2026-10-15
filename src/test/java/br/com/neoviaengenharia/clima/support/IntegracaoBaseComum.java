package br.com.neoviaengenharia.clima.support;

import br.com.neoviaengenharia.clima.ClimaServiceApplication;
import java.io.IOException;
import okhttp3.mockwebserver.MockWebServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Configuração comum: perfil de teste, relógio mutável e Nimbus simulado. */
@SpringBootTest(classes = ClimaServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(RelogioTesteConfig.class)
abstract class IntegracaoBaseComum {

    protected static final MockWebServer nimbus = new MockWebServer();

    static {
        try {
            nimbus.start();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao iniciar o MockWebServer do Nimbus", e);
        }
    }

    @Autowired
    protected RelogioMutavel relogio;

    static void registrarNimbus(DynamicPropertyRegistry registry) {
        registry.add("nimbus.base-url", () -> nimbus.url("/").toString());
    }
}

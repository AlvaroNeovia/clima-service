package br.com.neoviaengenharia.clima.ui.rest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.neoviaengenharia.clima.ui.exceptions.ParametrosInvalidosException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ParametrosConsultaTest {

    private static final Instant DE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void periodoAceitaFimIgualADe() {
        assertThatCode(() -> ParametrosConsulta.periodo(DE, DE)).doesNotThrowAnyException();
    }

    @Test
    void periodoAceitaExatamente90Dias() {
        assertThatCode(() -> ParametrosConsulta.periodo(DE, DE.plus(Duration.ofDays(90)))).doesNotThrowAnyException();
    }

    @Test
    void periodoAcimaDe90Dias400() {
        assertThatThrownBy(() -> ParametrosConsulta.periodo(DE, DE.plus(Duration.ofDays(90)).plusSeconds(1)))
                .isInstanceOf(ParametrosInvalidosException.class)
                .hasMessage("Parâmetros inválidos: o período máximo é de 90 dias");
    }

    @Test
    void periodoInvertido400() {
        assertThatThrownBy(() -> ParametrosConsulta.periodo(DE.plus(Duration.ofDays(1)), DE))
                .isInstanceOf(ParametrosInvalidosException.class)
                .hasMessage("Parâmetros inválidos: ate deve ser maior ou igual a de");
    }

    @Test
    void obrigatorioAusente() {
        assertThatThrownBy(() -> ParametrosConsulta.obrigatorio(null, "de"))
                .isInstanceOf(ParametrosInvalidosException.class)
                .hasMessage("Parâmetros inválidos: de é obrigatório");
        assertThatThrownBy(() -> ParametrosConsulta.obrigatorio("  ", "de"))
                .isInstanceOf(ParametrosInvalidosException.class)
                .hasMessage("Parâmetros inválidos: de é obrigatório");
        assertThatCode(() -> ParametrosConsulta.obrigatorio("2026-10-15T00:00:00Z", "de")).doesNotThrowAnyException();
    }
}
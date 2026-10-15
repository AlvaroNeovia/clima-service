package br.com.neoviaengenharia.clima.domain.observado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JanelaObservadaTest {

    @Test
    void aceitaJanelaDeUmDia() {
        JanelaObservada j = JanelaObservada.de("2026-10-14T00:00:00Z", "2026-10-15T00:00:00Z");
        assertThat(j.inicio()).isEqualTo(Instant.parse("2026-10-14T00:00:00Z"));
        assertThat(j.fim()).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
    }

    @Test
    void aceitaExatamente7Dias() {
        JanelaObservada j = JanelaObservada.de("2026-10-01T00:00:00Z", "2026-10-08T00:00:00Z");
        assertThat(j.fim()).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
    }

    @Test
    void recusaMaisDe7Dias() {
        assertThatThrownBy(() -> JanelaObservada.de("2026-10-01T00:00:00Z", "2026-10-09T00:00:00Z"))
                .isInstanceOf(JanelaExcedidaException.class)
                .hasMessage("Janela máxima de 7 dias");
    }

    @Test
    void recusaForaDeHoraCheia() {
        assertThatThrownBy(() -> JanelaObservada.de("2026-10-14T00:30:00Z", "2026-10-15T00:00:00Z"))
                .isInstanceOf(ParametrosInvalidosException.class)
                .hasMessageStartingWith("Parâmetros inválidos: ")
                .hasMessageContaining("inicio");
    }

    @Test
    void recusaSemFuso() {
        assertThatThrownBy(() -> JanelaObservada.de("2026-10-14T00:00:00", "2026-10-15T00:00:00Z"))
                .isInstanceOf(ParametrosInvalidosException.class)
                .hasMessageContaining("inicio");
    }

    @Test
    void recusaFimIgualOuAntesDoInicio() {
        assertThatThrownBy(() -> JanelaObservada.de("2026-10-14T00:00:00Z", "2026-10-14T00:00:00Z"))
                .isInstanceOf(ParametrosInvalidosException.class);
        assertThatThrownBy(() -> JanelaObservada.de("2026-10-14T00:00:00Z", "2026-10-13T00:00:00Z"))
                .isInstanceOf(ParametrosInvalidosException.class);
    }

    @Test
    void recusaAusente() {
        assertThatThrownBy(() -> JanelaObservada.de(null, "2026-10-15T00:00:00Z"))
                .isInstanceOf(ParametrosInvalidosException.class)
                .hasMessage("Parâmetros inválidos: inicio é obrigatório");
        assertThatThrownBy(() -> JanelaObservada.de("2026-10-14T00:00:00Z", " "))
                .isInstanceOf(ParametrosInvalidosException.class)
                .hasMessage("Parâmetros inválidos: fim é obrigatório");
    }

    @Test
    void aceitaOffsetEConverteParaUtc() {
        JanelaObservada j = JanelaObservada.de("2026-10-14T00:00:00-03:00", "2026-10-15T00:00:00-03:00");
        assertThat(j.inicio()).isEqualTo(Instant.parse("2026-10-14T03:00:00Z"));
    }

    @Test
    void offsetMeiaHoraForaDeHoraCheia() {
        assertThatThrownBy(() -> JanelaObservada.de("2026-10-14T00:00:00+05:30", "2026-10-15T00:00:00Z"))
                .isInstanceOf(ParametrosInvalidosException.class);
    }

    @Test
    void horasAteCortaNaHoraCorrente() {
        JanelaObservada j = JanelaObservada.de("2026-10-15T12:00:00Z", "2026-10-16T00:00:00Z");
        assertThat(j.horasAte(Instant.parse("2026-10-15T18:20:00Z")))
                .hasSize(7)
                .startsWith(Instant.parse("2026-10-15T12:00:00Z"))
                .endsWith(Instant.parse("2026-10-15T18:00:00Z"));
    }

    @Test
    void horasAteRespeitaFimExclusivo() {
        JanelaObservada j = JanelaObservada.de("2026-10-14T00:00:00Z", "2026-10-15T00:00:00Z");
        assertThat(j.horasAte(Instant.parse("2026-10-15T18:20:00Z")))
                .hasSize(24)
                .endsWith(Instant.parse("2026-10-14T23:00:00Z"));
    }

    @Test
    void janelaTodaNoFuturoNaoTemHoras() {
        JanelaObservada j = JanelaObservada.de("2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z");
        assertThat(j.horasAte(Instant.parse("2026-10-15T18:20:00Z"))).isEmpty();
    }
}

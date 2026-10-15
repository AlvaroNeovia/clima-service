package br.com.neoviaengenharia.clima.domain.previsao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FiltroJanelaTest {

    private static final Instant RODADA = Instant.parse("2026-10-15T00:00:00Z");

    /** Pontos horários de 15/10T00Z a 17/10T00Z (49 pontos). */
    private static List<PontoPrevisao> horarios() {
        List<PontoPrevisao> pontos = new ArrayList<>();
        for (Instant t = RODADA; !t.isAfter(Instant.parse("2026-10-17T00:00:00Z")); t = t.plus(Duration.ofHours(1))) {
            pontos.add(new PontoPrevisao(t, RODADA, Map.of()));
        }
        return pontos;
    }

    @Test
    void filtraInclusivo() {
        List<PontoPrevisao> r = FiltroJanela.aplicar(
                horarios(), Instant.parse("2026-10-16T00:00:00Z"), Instant.parse("2026-10-16T05:00:00Z"));

        assertThat(r).hasSize(6);
        assertThat(r.get(0).dataHora()).isEqualTo(Instant.parse("2026-10-16T00:00:00Z"));
        assertThat(r.get(5).dataHora()).isEqualTo(Instant.parse("2026-10-16T05:00:00Z"));
    }

    @Test
    void soInicio() {
        assertThat(FiltroJanela.aplicar(horarios(), Instant.parse("2026-10-16T23:00:00Z"), null)).hasSize(2);
    }

    @Test
    void soFim() {
        assertThat(FiltroJanela.aplicar(horarios(), null, Instant.parse("2026-10-15T01:00:00Z"))).hasSize(2);
    }

    @Test
    void semLimites() {
        assertThat(FiltroJanela.aplicar(horarios(), null, null)).hasSize(49);
    }

    @Test
    void janelaForaDosPontos() {
        assertThat(FiltroJanela.aplicar(
                horarios(), Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-02T00:00:00Z"))).isEmpty();
    }
}

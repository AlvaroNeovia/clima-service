package br.com.neoviaengenharia.clima.domain.observado;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanoBuscaTest {

    @Test
    void intervaloDaMenorAMaiorMaisUmaHora() {
        List<Instant> horas = new ArrayList<>();
        for (int i = 23; i >= 0; i--) {
            horas.add(Instant.parse("2026-10-10T00:00:00Z").plus(Duration.ofHours(i)));
        }
        PlanoBusca p = PlanoBusca.de(horas);
        assertThat(p.inicio()).isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));
        assertThat(p.fimExclusivo()).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
    }

    @Test
    void umaHoraSo() {
        PlanoBusca p = PlanoBusca.de(List.of(Instant.parse("2026-10-15T18:00:00Z")));
        assertThat(p.inicio()).isEqualTo(Instant.parse("2026-10-15T18:00:00Z"));
        assertThat(p.fimExclusivo()).isEqualTo(Instant.parse("2026-10-15T19:00:00Z"));
    }
}

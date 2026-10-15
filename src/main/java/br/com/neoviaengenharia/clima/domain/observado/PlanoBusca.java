package br.com.neoviaengenharia.clima.domain.observado;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;

/** Intervalo da chamada única à Nimbus (S4-FR-09): da menor hora a buscar à maior + 1 h (exclusivo). */
public record PlanoBusca(Instant inicio, Instant fimExclusivo) {

    public static PlanoBusca de(Collection<Instant> horasABuscar) {
        return new PlanoBusca(Collections.min(horasABuscar),
                Collections.max(horasABuscar).plus(Duration.ofHours(1)));
    }
}

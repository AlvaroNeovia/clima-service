package br.com.neoviaengenharia.clima.infra.repo;

import java.io.Serializable;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class TentativaObservadoId implements Serializable {

    private long regionId;
    private Instant dataHora;
}

package br.com.neoviaengenharia.clima.domain.regiao;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Catálogo de regiões não removidas, ordenadas por regionId, com o instante da busca. */
public record CatalogoSnapshot(List<RegiaoResumo> regioes, Instant obtidoEm) {

    public static final Duration VALIDADE = Duration.ofHours(24);

    public Instant validoAte() {
        return obtidoEm.plus(VALIDADE);
    }

    public boolean vigente(Instant agora) {
        return agora.isBefore(validoAte());
    }
}

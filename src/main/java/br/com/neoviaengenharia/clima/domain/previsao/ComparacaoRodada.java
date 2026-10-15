package br.com.neoviaengenharia.clima.domain.previsao;

import java.time.Instant;
import java.util.Optional;

/** Rodada recebida da Nimbus em relação à vigente gravada (S2-FR-07/08). */
public enum ComparacaoRodada {
    NOVA, MESMA, ANTIGA;

    public static ComparacaoRodada de(Optional<Instant> vigente, Instant recebida) {
        if (vigente.isEmpty() || recebida.isAfter(vigente.get())) {
            return NOVA;
        }
        return recebida.equals(vigente.get()) ? MESMA : ANTIGA;
    }
}

package br.com.neoviaengenharia.clima.domain.previsao;

import java.time.Instant;
import java.util.List;

/** Filtro {@code inicio <= dataHora <= fim}; limite {@code null} não restringe (S2-FR-11). */
public final class FiltroJanela {

    private FiltroJanela() {}

    public static List<PontoPrevisao> aplicar(List<PontoPrevisao> pontos, Instant inicio, Instant fim) {
        return pontos.stream()
                .filter(p -> inicio == null || !p.dataHora().isBefore(inicio))
                .filter(p -> fim == null || !p.dataHora().isAfter(fim))
                .toList();
    }
}

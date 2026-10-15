package br.com.neoviaengenharia.clima.domain.previsao;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Rodada gravada de uma região: pontos em ordem crescente de {@code dataHora}. */
public record RodadaPrevisao(long regionId, Instant rodada, Instant obtidoEm, List<PontoPrevisao> pontos) {

    public RodadaPrevisao {
        pontos = pontos.stream().sorted(Comparator.comparing(PontoPrevisao::dataHora)).toList();
    }

    /** A rodada da região é o maior {@code run_datetime} entre os pontos recebidos (S2-FR-03). */
    public static Instant rodadaDe(List<PontoPrevisao> pontos) {
        return pontos.stream()
                .map(PontoPrevisao::rodadaPonto)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalArgumentException("Sem pontos para identificar a rodada"));
    }

    public RodadaPrevisao comPontos(List<PontoPrevisao> outros) {
        return new RodadaPrevisao(regionId, rodada, obtidoEm, outros);
    }
}

package br.com.neoviaengenharia.clima.domain.previsao;

import java.time.Instant;

/** Identificação de uma rodada gravada, sem os pontos (listagem de rodadas). */
public record RodadaResumo(Instant rodada, Instant obtidoEm) {}
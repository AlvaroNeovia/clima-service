package br.com.neoviaengenharia.clima.ui.dto;

import java.time.Instant;

/** Identificação de uma rodada gravada (spec S3 §5.1). */
public record RodadaResumoResponse(Instant rodada, Instant obtidoEm) {}
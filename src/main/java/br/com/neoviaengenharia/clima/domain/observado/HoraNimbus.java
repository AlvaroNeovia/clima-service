package br.com.neoviaengenharia.clima.domain.observado;

import java.time.Instant;

/** Item de {@code data} do data-consistency, já validado. */
public record HoraNimbus(Instant dataHora, LeituraHora leitura) {}

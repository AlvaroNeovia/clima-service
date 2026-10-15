package br.com.neoviaengenharia.clima.app.observado;

import br.com.neoviaengenharia.clima.domain.observado.EstadoHora;
import br.com.neoviaengenharia.clima.domain.observado.JanelaObservada;
import java.util.List;

/** Série observada: só horas com dado, em ordem crescente; {@code desatualizado} se a Nimbus falhou (S4-FR-12). */
public record ObservadoResultado(long regionId, JanelaObservada janela, boolean desatualizado, List<EstadoHora> horas) {}

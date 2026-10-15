package br.com.neoviaengenharia.clima.domain.previsao;

import java.time.Instant;

/**
 * Entrada de cache da rodada vigente de uma região: vale até {@code validoAte} (virada do dia UTC ou fim da
 * janela de 15 min), e {@code desatualizado} repete o resultado da última tentativa na Nimbus.
 */
public record PrevisaoVigente(RodadaPrevisao rodada, Instant validoAte, boolean desatualizado) {

    public boolean valida(Instant agora) {
        return agora.isBefore(validoAte);
    }
}

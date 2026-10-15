package br.com.neoviaengenharia.clima.app.previsao;

import br.com.neoviaengenharia.clima.domain.previsao.RodadaPrevisao;

/**
 * Resultado de uma região. {@code rodada} é {@code null} quando há erro ou a Nimbus não tem dado para a região;
 * os pontos já vêm filtrados pela janela pedida.
 */
public record ItemPrevisao(long regionId, RodadaPrevisao rodada, boolean desatualizado, CodigoErroRegiao erro) {

    static ItemPrevisao erro(long regionId, CodigoErroRegiao codigo) {
        return new ItemPrevisao(regionId, null, false, codigo);
    }

    static ItemPrevisao semDado(long regionId) {
        return new ItemPrevisao(regionId, null, false, null);
    }

    static ItemPrevisao com(RodadaPrevisao rodada, boolean desatualizado) {
        return new ItemPrevisao(rodada.regionId(), rodada, desatualizado, null);
    }
}

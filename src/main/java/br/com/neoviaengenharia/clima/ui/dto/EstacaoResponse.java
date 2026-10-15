package br.com.neoviaengenharia.clima.ui.dto;

import br.com.neoviaengenharia.clima.domain.observado.Estacao;

public record EstacaoResponse(long id, String nome, String codigo) {

    public static EstacaoResponse de(Estacao e) {
        return new EstacaoResponse(e.id(), e.nome(), e.codigo());
    }
}

package br.com.neoviaengenharia.clima.ui.dto;

import br.com.neoviaengenharia.clima.app.previsao.ConsultaPrevisao;
import java.util.List;

public record PrevisoesResponse(List<PrevisaoRegiaoResponse> regioes) {

    public static PrevisoesResponse de(ConsultaPrevisao consulta) {
        return new PrevisoesResponse(consulta.itens().stream().map(PrevisaoRegiaoResponse::de).toList());
    }
}

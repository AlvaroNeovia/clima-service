package br.com.neoviaengenharia.clima.ui.dto;

import br.com.neoviaengenharia.clima.domain.regiao.RegiaoResumo;

public record RegiaoResponse(long regionId, String nome, String alias, boolean inativa, long estadoId) {

    public static RegiaoResponse de(RegiaoResumo r) {
        return new RegiaoResponse(r.regionId(), r.nome(), r.alias(), r.inativa(), r.estadoId());
    }
}

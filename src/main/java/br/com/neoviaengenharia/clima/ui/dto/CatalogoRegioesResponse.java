package br.com.neoviaengenharia.clima.ui.dto;

import br.com.neoviaengenharia.clima.app.regiao.CatalogoResultado;
import java.time.Instant;
import java.util.List;

public record CatalogoRegioesResponse(List<RegiaoResponse> regioes, Instant obtidoEm, boolean desatualizado) {

    public static CatalogoRegioesResponse de(CatalogoResultado r) {
        return new CatalogoRegioesResponse(
                r.snapshot().regioes().stream().map(RegiaoResponse::de).toList(),
                r.snapshot().obtidoEm(),
                r.desatualizado());
    }
}

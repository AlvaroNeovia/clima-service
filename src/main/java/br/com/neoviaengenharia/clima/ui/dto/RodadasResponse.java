package br.com.neoviaengenharia.clima.ui.dto;

import br.com.neoviaengenharia.clima.domain.previsao.RodadaResumo;
import java.util.List;

/** Rodadas gravadas da região no período pedido, da mais recente para a mais antiga (spec S3 §5.1). */
public record RodadasResponse(long regionId, List<RodadaResumoResponse> rodadas) {

    public static RodadasResponse de(long regionId, List<RodadaResumo> rodadas) {
        return new RodadasResponse(regionId, rodadas.stream().map(r -> new RodadaResumoResponse(r.rodada(), r.obtidoEm()))
                .toList());
    }
}
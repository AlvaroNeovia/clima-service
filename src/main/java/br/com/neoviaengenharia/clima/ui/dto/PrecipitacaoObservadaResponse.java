package br.com.neoviaengenharia.clima.ui.dto;

import br.com.neoviaengenharia.clima.app.observado.ObservadoResultado;
import java.time.Instant;
import java.util.List;

public record PrecipitacaoObservadaResponse(
        long regionId, Instant inicio, Instant fim, boolean desatualizado, List<HoraObservadaResponse> horas) {

    public static PrecipitacaoObservadaResponse de(ObservadoResultado r) {
        return new PrecipitacaoObservadaResponse(r.regionId(), r.janela().inicio(), r.janela().fim(),
                r.desatualizado(), r.horas().stream().map(HoraObservadaResponse::de).toList());
    }
}

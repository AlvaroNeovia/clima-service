package br.com.neoviaengenharia.clima.ui.dto;

import br.com.neoviaengenharia.clima.domain.observado.EstadoHora;
import br.com.neoviaengenharia.clima.domain.observado.LeituraHora;
import java.math.BigDecimal;
import java.time.Instant;

/** Uma hora observada; acumulado e máximo valem para aquela hora, e {@code null} da Nimbus é preservado. */
public record HoraObservadaResponse(
        Instant dataHora,
        EstacaoResponse estacao,
        BigDecimal precipitacaoAcumulada,
        BigDecimal precipitacaoMaxima,
        Instant dataHoraMaxima,
        BigDecimal primeiraLeitura,
        Instant dataHoraPrimeiraLeitura,
        Boolean interpolado,
        Instant obtidoEm) {

    public static HoraObservadaResponse de(EstadoHora e) {
        LeituraHora l = e.leitura();
        return new HoraObservadaResponse(e.dataHora(), EstacaoResponse.de(l.estacao()), l.precipitacaoAcumulada(),
                l.precipitacaoMaxima(), l.dataHoraMaxima(), l.primeiraLeitura(), l.dataHoraPrimeiraLeitura(),
                l.interpolado(), e.obtidoEm());
    }
}

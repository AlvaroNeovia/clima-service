package br.com.neoviaengenharia.clima.domain.observado;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Valores observados de uma hora. Os decimais são normalizados (4 casas, sem zeros à direita), para que o
 * valor recebido da Nimbus e o relido do {@code DECIMAL(12,4)} sejam iguais; {@code null} é preservado.
 */
public record LeituraHora(
        Estacao estacao,
        BigDecimal precipitacaoAcumulada,
        BigDecimal precipitacaoMaxima,
        Instant dataHoraMaxima,
        BigDecimal primeiraLeitura,
        Instant dataHoraPrimeiraLeitura,
        Boolean interpolado) {

    public LeituraHora {
        precipitacaoAcumulada = normalizar(precipitacaoAcumulada);
        precipitacaoMaxima = normalizar(precipitacaoMaxima);
        primeiraLeitura = normalizar(primeiraLeitura);
    }

    private static BigDecimal normalizar(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal n = v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        return n.scale() < 0 ? n.setScale(0) : n;
    }
}

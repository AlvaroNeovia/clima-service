package br.com.neoviaengenharia.clima.domain.observado;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LeituraHoraTest {

    private static LeituraHora com(String valor) {
        return new LeituraHora(new Estacao(1, "a", "b"), valor == null ? null : new BigDecimal(valor),
                null, null, null, null, false);
    }

    @Test
    void normalizaDecimais() {
        assertThat(com("0.0000").precipitacaoAcumulada()).isEqualTo(new BigDecimal("0"));
        assertThat(com("100.0000").precipitacaoAcumulada().toString()).isEqualTo("100");
        assertThat(com("0.12345").precipitacaoAcumulada()).isEqualTo(new BigDecimal("0.1235"));
        assertThat(com("1.25").precipitacaoAcumulada()).isEqualTo(new BigDecimal("1.25"));
        assertThat(com(null).precipitacaoAcumulada()).isNull();
    }

    @Test
    void zeroDaNimbusEZeroDoBancoSaoIguais() {
        assertThat(com("0")).isEqualTo(com("0.0000"));
    }
}

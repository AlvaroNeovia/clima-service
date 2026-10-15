package br.com.neoviaengenharia.clima.domain.previsao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TraducaoVariaveisTest {

    @Test
    void traduzNulosEDesconhecidas() {
        Map<String, Object> entrada = new LinkedHashMap<>();
        entrada.put("temperature", 35);
        entrada.put("fog_fsi", null);
        entrada.put("pressure", 1012);

        Map<String, Object> saida = TraducaoVariaveis.traduzir(entrada);

        assertThat(saida).containsExactly(
                Map.entry("temperatura", 35), new java.util.AbstractMap.SimpleEntry<>("nevoeiroFsi", null),
                Map.entry("pressure", 1012));
        assertThat(saida).containsKey("nevoeiroFsi").doesNotContainKey("rajadaVento");
    }

    @Test
    void traduzTodasAsChavesDaTabela() {
        Map<String, Object> entrada = new LinkedHashMap<>();
        entrada.put("wind_direction", 297);
        entrada.put("temperature", 35);
        entrada.put("wind_gust", 18.14);
        entrada.put("wind_speed", 10.8);
        entrada.put("relative_humidity", 24.19);
        entrada.put("precipitation", 0.2);
        entrada.put("fog_fsi", 68.63);
        entrada.put("fog_vis", 5);

        assertThat(TraducaoVariaveis.traduzir(entrada)).containsExactly(
                Map.entry("direcaoVento", 297), Map.entry("temperatura", 35), Map.entry("rajadaVento", 18.14),
                Map.entry("velocidadeVento", 10.8), Map.entry("umidadeRelativa", 24.19),
                Map.entry("precipitacao", 0.2), Map.entry("nevoeiroFsi", 68.63),
                Map.entry("nevoeiroVisibilidade", 5));
    }
}

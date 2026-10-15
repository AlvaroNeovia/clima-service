package br.com.neoviaengenharia.clima.domain.previsao;

import java.util.LinkedHashMap;
import java.util.Map;

/** Nomes das variáveis da Nimbus no contrato Neovia (spec S2 §6.3). Chave desconhecida sai com o nome original. */
public final class TraducaoVariaveis {

    private static final Map<String, String> NOMES = Map.of(
            "wind_direction", "direcaoVento",
            "temperature", "temperatura",
            "wind_gust", "rajadaVento",
            "wind_speed", "velocidadeVento",
            "relative_humidity", "umidadeRelativa",
            "precipitation", "precipitacao",
            "fog_fsi", "nevoeiroFsi",
            "fog_vis", "nevoeiroVisibilidade");

    private TraducaoVariaveis() {}

    public static Map<String, Object> traduzir(Map<String, Object> originais) {
        Map<String, Object> traduzidas = new LinkedHashMap<>();
        originais.forEach((chave, valor) -> traduzidas.put(NOMES.getOrDefault(chave, chave), valor));
        return traduzidas;
    }
}

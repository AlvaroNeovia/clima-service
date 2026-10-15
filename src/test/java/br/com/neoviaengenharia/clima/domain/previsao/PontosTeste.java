package br.com.neoviaengenharia.clima.domain.previsao;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Atalhos para montar pontos nos testes. */
public final class PontosTeste {

    private PontosTeste() {}

    public static PontoPrevisao ponto(String dataHora, String rodada, Object... chavesEValores) {
        Map<String, Object> variaveis = new LinkedHashMap<>();
        for (int i = 0; i < chavesEValores.length; i += 2) {
            variaveis.put((String) chavesEValores[i], chavesEValores[i + 1]);
        }
        return new PontoPrevisao(Instant.parse(dataHora), Instant.parse(rodada), variaveis);
    }
}

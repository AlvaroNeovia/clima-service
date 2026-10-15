package br.com.neoviaengenharia.clima.domain.previsao;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Um horário previsto. {@code variaveis} usa as chaves originais da Nimbus e pode ter valores {@code null}
 * (variável configurada sem dado).
 */
public record PontoPrevisao(Instant dataHora, Instant rodadaPonto, Map<String, Object> variaveis) {

    public PontoPrevisao {
        variaveis = Collections.unmodifiableMap(new LinkedHashMap<>(variaveis));
    }
}

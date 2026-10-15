package br.com.neoviaengenharia.clima.ui.dto;

import br.com.neoviaengenharia.clima.domain.previsao.PontoPrevisao;
import br.com.neoviaengenharia.clima.domain.previsao.TraducaoVariaveis;
import java.time.Instant;
import java.util.Map;

/** Ponto com as variáveis já traduzidas para o contrato Neovia ({@code null} preservado). */
public record PontoPrevisaoResponse(Instant dataHora, Instant rodadaPonto, Map<String, Object> variaveis) {

    public static PontoPrevisaoResponse de(PontoPrevisao p) {
        return new PontoPrevisaoResponse(p.dataHora(), p.rodadaPonto(), TraducaoVariaveis.traduzir(p.variaveis()));
    }
}

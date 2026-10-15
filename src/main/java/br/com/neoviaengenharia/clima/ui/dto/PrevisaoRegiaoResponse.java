package br.com.neoviaengenharia.clima.ui.dto;

import br.com.neoviaengenharia.clima.app.previsao.ItemPrevisao;
import java.time.Instant;
import java.util.List;

/** Item de região (spec S2 §5.1). Sem rodada, {@code rodada}/{@code obtidoEm} saem {@code null} e os pontos vazios. */
public record PrevisaoRegiaoResponse(
        long regionId,
        Instant rodada,
        Instant obtidoEm,
        boolean desatualizado,
        ErroRegiaoResponse erro,
        List<PontoPrevisaoResponse> pontos) {

    public static PrevisaoRegiaoResponse de(ItemPrevisao item) {
        ErroRegiaoResponse erro = item.erro() == null ? null
                : new ErroRegiaoResponse(item.erro().name(), item.erro().mensagem());
        if (item.rodada() == null) {
            return new PrevisaoRegiaoResponse(item.regionId(), null, null, item.desatualizado(), erro, List.of());
        }
        return new PrevisaoRegiaoResponse(item.regionId(), item.rodada().rodada(), item.rodada().obtidoEm(),
                item.desatualizado(), erro, item.rodada().pontos().stream().map(PontoPrevisaoResponse::de).toList());
    }
}

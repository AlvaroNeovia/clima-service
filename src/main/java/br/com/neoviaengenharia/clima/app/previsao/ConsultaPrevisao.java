package br.com.neoviaengenharia.clima.app.previsao;

import java.util.List;

/** Itens na ordem pedida. */
public record ConsultaPrevisao(List<ItemPrevisao> itens) {

    /** Todas as regiões ficaram sem dado por falha da fonte: a resposta inteira é 503 (S2-FR-15). */
    public boolean todasIndisponiveis() {
        return !itens.isEmpty() && itens.stream().allMatch(i -> i.erro() == CodigoErroRegiao.FONTE_INDISPONIVEL);
    }
}

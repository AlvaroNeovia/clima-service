package br.com.neoviaengenharia.clima.domain.regiao;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reconcilia as regiões gravadas com o catálogo recebido da Nimbus (S1-FR-06, S1-FR-07). */
public final class ReconciliacaoCatalogo {

    private ReconciliacaoCatalogo() {}

    /** Devolve todas as linhas a salvar: inseridas, atualizadas (inclusive reativadas) e marcadas removidas. */
    public static List<Regiao> aplicar(List<Regiao> gravadas, List<RegiaoNimbus> recebidas, Instant agora) {
        Map<Long, Regiao> porId = new HashMap<>();
        gravadas.forEach(r -> porId.put(r.getRegionId(), r));

        List<Regiao> salvar = new ArrayList<>();
        Set<Long> recebidasIds = new HashSet<>();
        for (RegiaoNimbus item : recebidas) {
            recebidasIds.add(item.regionId());
            Regiao existente = porId.get(item.regionId());
            if (existente == null) {
                Regiao nova = Regiao.nova(item, agora);
                porId.put(item.regionId(), nova);
                salvar.add(nova);
            } else {
                existente.atualizarCom(item, agora);
                salvar.add(existente);
            }
        }
        for (Regiao r : gravadas) {
            if (!recebidasIds.contains(r.getRegionId()) && !r.isRemovida()) {
                r.marcarRemovida(agora);
                salvar.add(r);
            }
        }
        return salvar;
    }
}

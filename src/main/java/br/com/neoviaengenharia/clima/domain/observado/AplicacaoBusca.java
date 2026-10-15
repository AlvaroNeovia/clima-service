package br.com.neoviaengenharia.clima.domain.observado;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Aplica o resultado de uma busca bem-sucedida na Nimbus às horas buscadas (S4-FR-06..09). */
public final class AplicacaoBusca {

    private AplicacaoBusca() {}

    /**
     * Devolve os novos estados das horas buscadas. Recebidas trazem leitura nova; ausentes antigas viram
     * {@code semDado}; ausentes recentes ou correntes mantêm o que tinham. Todas registram a tentativa em
     * {@code agora}. Itens não buscados (definitivos, fora do intervalo, fora de hora cheia) são ignorados;
     * com {@code datetime} repetido, vale o último.
     */
    public static Map<Instant, EstadoHora> aplicar(
            Map<Instant, EstadoHora> atuais, Set<Instant> buscadas, Iterable<HoraNimbus> recebidas, Instant agora) {
        Map<Instant, LeituraHora> porHora = new HashMap<>();
        for (HoraNimbus r : recebidas) {
            if (buscadas.contains(r.dataHora())) {
                porHora.put(r.dataHora(), r.leitura());
            }
        }
        Map<Instant, EstadoHora> alterados = new HashMap<>();
        for (Instant h : buscadas) {
            LeituraHora nova = porHora.get(h);
            EstadoHora atual = atuais.getOrDefault(h, EstadoHora.vazio(h));
            EstadoHora novo;
            if (nova != null) {
                novo = new EstadoHora(h, nova, agora, false, agora);
            } else if (EstadoHora.antiga(h, agora)) {
                novo = new EstadoHora(h, null, null, true, agora);
            } else {
                novo = new EstadoHora(h, atual.leitura(), atual.obtidoEm(), false, agora);
            }
            alterados.put(h, novo);
        }
        return alterados;
    }
}

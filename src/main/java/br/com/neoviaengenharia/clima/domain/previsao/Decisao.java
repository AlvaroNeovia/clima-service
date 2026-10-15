package br.com.neoviaengenharia.clima.domain.previsao;

import java.time.Instant;

/**
 * Buscar na Nimbus ou usar o gravado. Ao usar o gravado, {@code validoAte} é até quando a decisão vale e
 * {@code desatualizado} indica que a última tentativa na Nimbus falhou.
 */
public record Decisao(boolean buscar, Instant validoAte, boolean desatualizado) {

    public static Decisao buscarNaNimbus() {
        return new Decisao(true, null, false);
    }

    public static Decisao usarGravada(Instant validoAte, boolean desatualizado) {
        return new Decisao(false, validoAte, desatualizado);
    }
}

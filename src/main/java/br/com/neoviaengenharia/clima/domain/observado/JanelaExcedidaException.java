package br.com.neoviaengenharia.clima.domain.observado;

/** Janela de observado acima de 7 dias (400). */
public class JanelaExcedidaException extends RuntimeException {

    public JanelaExcedidaException() {
        super("Janela máxima de 7 dias");
    }
}

package br.com.neoviaengenharia.clima.app.observado;

/** Região inexistente em {@code regiao}, ou recusada pela Nimbus (404). */
public class RegiaoNaoEncontradaException extends RuntimeException {

    public RegiaoNaoEncontradaException() {
        super("Região não encontrada");
    }
}

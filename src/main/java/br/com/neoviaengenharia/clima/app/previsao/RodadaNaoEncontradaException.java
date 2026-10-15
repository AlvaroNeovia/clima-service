package br.com.neoviaengenharia.clima.app.previsao;

/** A região existe, mas não tem a rodada pedida (S3-FR-05). */
public class RodadaNaoEncontradaException extends RuntimeException {

    public RodadaNaoEncontradaException() {
        super("Rodada não encontrada para a região");
    }
}
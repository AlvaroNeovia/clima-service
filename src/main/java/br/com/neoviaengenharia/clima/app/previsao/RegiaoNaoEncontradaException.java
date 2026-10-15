package br.com.neoviaengenharia.clima.app.previsao;

/** Região ausente em {@code regiao} (S3-FR-06). Vale também para região removida sem linha. */
public class RegiaoNaoEncontradaException extends RuntimeException {

    public RegiaoNaoEncontradaException() {
        super("Região não encontrada");
    }
}
package br.com.neoviaengenharia.clima.ui.exceptions;

/** Parâmetro de consulta ausente ou inválido (400 "Parâmetros inválidos: <detalhe>"). */
public class ParametrosInvalidosException extends RuntimeException {

    public ParametrosInvalidosException(String detalhe) {
        super("Parâmetros inválidos: " + detalhe);
    }
}

package br.com.neoviaengenharia.clima.domain.observado;

/** Parâmetro da consulta ausente ou inválido (400). */
public class ParametrosInvalidosException extends RuntimeException {

    public ParametrosInvalidosException(String detalhe) {
        super("Parâmetros inválidos: " + detalhe);
    }
}

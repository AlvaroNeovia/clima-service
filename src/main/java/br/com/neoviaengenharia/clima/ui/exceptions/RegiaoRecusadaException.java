package br.com.neoviaengenharia.clima.ui.exceptions;

import br.com.neoviaengenharia.clima.app.regiao.MotivoRecusa;

/** Região recusada na validação; o {@link ExceptionManager} mapeia para 404 ou 422. */
public class RegiaoRecusadaException extends RuntimeException {

    private final MotivoRecusa motivo;

    public RegiaoRecusadaException(MotivoRecusa motivo) {
        super(motivo.name());
        this.motivo = motivo;
    }

    public MotivoRecusa motivo() {
        return motivo;
    }
}

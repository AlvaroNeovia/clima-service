package br.com.neoviaengenharia.clima.infra.nimbus;

/** Falha ao falar com a Nimbus. Nunca carrega a causa original nem valores de header. */
public class NimbusIndisponivelException extends RuntimeException {

    public enum Motivo { TIMEOUT, REDE, ERRO_HTTP, CREDENCIAL_RECUSADA, CORPO_INVALIDO }

    private final Motivo motivo;
    private final Integer status;

    public NimbusIndisponivelException(Motivo motivo, Integer status) {
        super("Nimbus indisponível: " + motivo + (status == null ? "" : " [" + status + "]"));
        this.motivo = motivo;
        this.status = status;
    }

    public Motivo motivo() {
        return motivo;
    }

    public Integer status() {
        return status;
    }
}

package br.com.neoviaengenharia.clima.infra.nimbus;

/** A Nimbus recusou a região (não atribuída às credenciais): HTTP 404 no data-consistency. */
public class NimbusRegiaoRecusadaException extends RuntimeException {

    private final long regionId;

    public NimbusRegiaoRecusadaException(long regionId) {
        super("Nimbus recusou a região " + regionId);
        this.regionId = regionId;
    }

    public long regionId() {
        return regionId;
    }
}

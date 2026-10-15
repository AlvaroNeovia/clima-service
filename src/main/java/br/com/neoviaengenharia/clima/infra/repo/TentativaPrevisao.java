package br.com.neoviaengenharia.clima.infra.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Última chamada à Nimbus para a região (S2-FR-05) e se ela falhou. Também serve de trava entre instâncias. */
@Entity
@Table(name = "tentativa_previsao")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TentativaPrevisao {

    @Id
    @Column(name = "region_id")
    private long regionId;

    @Column(name = "ultima_tentativa_em")
    private Instant ultimaTentativaEm;

    @Column(name = "ultima_falhou", nullable = false)
    private boolean ultimaFalhou;

    public void registrar(Instant quando, boolean falhou) {
        this.ultimaTentativaEm = quando;
        this.ultimaFalhou = falhou;
    }
}

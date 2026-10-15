package br.com.neoviaengenharia.clima.infra.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Linha única (id = 1): trava entre instâncias e registro da última falha. */
@Entity
@Table(name = "catalogo_regioes_controle")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ControleCatalogo {

    @Id
    private int id;

    @Column(name = "ultima_falha_em")
    private Instant ultimaFalhaEm;

    public void registrarFalha(Instant quando) {
        this.ultimaFalhaEm = quando;
    }

    public void limparFalha() {
        this.ultimaFalhaEm = null;
    }
}

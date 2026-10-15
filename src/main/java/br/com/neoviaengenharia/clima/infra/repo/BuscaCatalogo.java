package br.com.neoviaengenharia.clima.infra.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "catalogo_regioes_busca")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuscaCatalogo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "obtido_em", nullable = false)
    private Instant obtidoEm;

    @Column(name = "total_regioes", nullable = false)
    private int totalRegioes;

    public BuscaCatalogo(Instant obtidoEm, int totalRegioes) {
        this.obtidoEm = obtidoEm;
        this.totalRegioes = totalRegioes;
    }
}

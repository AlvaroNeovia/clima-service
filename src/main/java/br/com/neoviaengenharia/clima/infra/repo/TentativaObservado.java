package br.com.neoviaengenharia.clima.infra.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Última chamada bem-sucedida à Nimbus que cobriu a hora (S4 §7.2). */
@Entity
@Table(name = "tentativa_observado")
@IdClass(TentativaObservadoId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TentativaObservado {

    @Id
    @Column(name = "region_id")
    private long regionId;

    @Id
    @Column(name = "data_hora")
    private Instant dataHora;

    @Column(name = "ultima_tentativa_em", nullable = false)
    private Instant ultimaTentativaEm;

    public TentativaObservado(long regionId, Instant dataHora, Instant ultimaTentativaEm) {
        this.regionId = regionId;
        this.dataHora = dataHora;
        this.ultimaTentativaEm = ultimaTentativaEm;
    }

    public void registrar(Instant quando) {
        this.ultimaTentativaEm = quando;
    }
}

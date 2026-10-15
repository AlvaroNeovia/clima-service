package br.com.neoviaengenharia.clima.domain.regiao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "regiao")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Regiao {

    @Id
    @Column(name = "region_id")
    private long regionId;

    @Column(name = "nome", nullable = false)
    private String nome;

    @Column(name = "alias", nullable = false)
    private String alias;

    @Column(name = "inativa", nullable = false)
    private boolean inativa;

    @Column(name = "estado_id", nullable = false)
    private long estadoId;

    @Column(name = "removida", nullable = false)
    private boolean removida;

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm;

    public static Regiao nova(RegiaoNimbus origem, Instant agora) {
        Regiao r = new Regiao();
        r.regionId = origem.regionId();
        r.atualizarCom(origem, agora);
        return r;
    }

    public void atualizarCom(RegiaoNimbus origem, Instant agora) {
        this.nome = origem.nome();
        this.alias = origem.alias();
        this.inativa = origem.inativa();
        this.estadoId = origem.estadoId();
        this.removida = false;
        this.atualizadoEm = agora;
    }

    public void marcarRemovida(Instant agora) {
        this.removida = true;
        this.atualizadoEm = agora;
    }

    public RegiaoResumo resumo() {
        return new RegiaoResumo(regionId, nome, alias, inativa, estadoId);
    }
}

package br.com.neoviaengenharia.clima.infra.repo;

import br.com.neoviaengenharia.clima.domain.observado.EstadoHora;
import br.com.neoviaengenharia.clima.domain.observado.Estacao;
import br.com.neoviaengenharia.clima.domain.observado.LeituraHora;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Hora observada gravada (S4 §7.1). Com {@code semDado}, os campos de dado ficam nulos. */
@Entity
@Table(name = "hora_observada")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HoraObservada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "region_id", nullable = false)
    private long regionId;

    @Column(name = "data_hora", nullable = false)
    private Instant dataHora;

    @Column(name = "sem_dado", nullable = false)
    private boolean semDado;

    @Column(name = "station_id")
    private Long stationId;

    @Column(name = "station_name")
    private String stationName;

    @Column(name = "station_code")
    private String stationCode;

    @Column(name = "precipitation_sum", precision = 12, scale = 4)
    private BigDecimal precipitationSum;

    @Column(name = "precipitation_max", precision = 12, scale = 4)
    private BigDecimal precipitationMax;

    @Column(name = "precipitation_max_datetime")
    private Instant precipitationMaxDatetime;

    @Column(name = "precipitation_first", precision = 12, scale = 4)
    private BigDecimal precipitationFirst;

    @Column(name = "precipitation_first_datetime")
    private Instant precipitationFirstDatetime;

    @Column(name = "is_interpolated")
    private Boolean isInterpolated;

    @Column(name = "obtido_em", nullable = false)
    private Instant obtidoEm;

    public static HoraObservada de(long regionId, EstadoHora e) {
        HoraObservada h = new HoraObservada();
        h.regionId = regionId;
        h.dataHora = e.dataHora();
        h.atualizarCom(e);
        return h;
    }

    /** Copia o estado; numa hora {@code semDado}, {@code obtidoEm} é o instante da tentativa que a marcou. */
    public void atualizarCom(EstadoHora e) {
        this.semDado = e.semDado();
        this.obtidoEm = e.obtidoEm() != null ? e.obtidoEm() : e.ultimaTentativaEm();
        LeituraHora l = e.leitura();
        this.stationId = l == null ? null : l.estacao().id();
        this.stationName = l == null ? null : l.estacao().nome();
        this.stationCode = l == null ? null : l.estacao().codigo();
        this.precipitationSum = l == null ? null : l.precipitacaoAcumulada();
        this.precipitationMax = l == null ? null : l.precipitacaoMaxima();
        this.precipitationMaxDatetime = l == null ? null : l.dataHoraMaxima();
        this.precipitationFirst = l == null ? null : l.primeiraLeitura();
        this.precipitationFirstDatetime = l == null ? null : l.dataHoraPrimeiraLeitura();
        this.isInterpolated = l == null ? null : l.interpolado();
    }

    /** Leitura da hora, ou {@code null} se {@code semDado}. */
    public LeituraHora leitura() {
        if (semDado || stationId == null) {
            return null;
        }
        return new LeituraHora(new Estacao(stationId, stationName, stationCode), precipitationSum,
                precipitationMax, precipitationMaxDatetime, precipitationFirst, precipitationFirstDatetime,
                isInterpolated);
    }

    public EstadoHora estado(Instant ultimaTentativaEm) {
        LeituraHora l = leitura();
        return new EstadoHora(dataHora, l, l == null ? null : obtidoEm, semDado, ultimaTentativaEm);
    }
}

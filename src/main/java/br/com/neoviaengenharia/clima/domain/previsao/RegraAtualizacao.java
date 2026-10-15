package br.com.neoviaengenharia.clima.domain.previsao;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/** Regras de atualização da previsão, sempre em UTC (S2-FR-04/05/06, FR-018). */
public final class RegraAtualizacao {

    public static final Duration INTERVALO_RECONSULTA = Duration.ofMinutes(15);
    static final Duration HORIZONTE = Duration.ofDays(15);

    private RegraAtualizacao() {}

    public static Decisao decidir(
            Optional<Instant> rodadaVigente, Optional<Instant> ultimaTentativa, boolean ultimaFalhou, Instant agora) {
        LocalDate hoje = dataUtc(agora);
        if (rodadaVigente.isPresent() && !dataUtc(rodadaVigente.get()).isBefore(hoje)) {
            return Decisao.usarGravada(inicioJanela(agora).plus(Duration.ofDays(1)), false);
        }
        if (ultimaTentativa.isPresent()) {
            Instant limite = ultimaTentativa.get().plus(INTERVALO_RECONSULTA);
            if (agora.isBefore(limite)) {
                return Decisao.usarGravada(limite, ultimaFalhou);
            }
        }
        return Decisao.buscarNaNimbus();
    }

    /** 00:00:00Z do dia UTC de {@code agora}. */
    public static Instant inicioJanela(Instant agora) {
        return dataUtc(agora).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public static Instant fimJanela(Instant agora) {
        return inicioJanela(agora).plus(HORIZONTE);
    }

    private static LocalDate dataUtc(Instant instante) {
        return LocalDate.ofInstant(instante, ZoneOffset.UTC);
    }
}

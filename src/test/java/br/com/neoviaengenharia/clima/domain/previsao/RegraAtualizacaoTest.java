package br.com.neoviaengenharia.clima.domain.previsao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RegraAtualizacaoTest {

    private static final Instant RODADA_15 = Instant.parse("2026-10-15T00:00:00Z");
    private static final Instant RODADA_14 = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void rodadaDeHojeNaoBusca() {
        Decisao d = RegraAtualizacao.decidir(
                Optional.of(RODADA_15), Optional.empty(), false, Instant.parse("2026-10-15T18:00:00Z"));

        assertThat(d.buscar()).isFalse();
        assertThat(d.validoAte()).isEqualTo(Instant.parse("2026-10-16T00:00:00Z"));
        assertThat(d.desatualizado()).isFalse();
    }

    @Test
    void rodadaDeHojeValeAteMeiaNoiteExclusive() {
        Decisao d = RegraAtualizacao.decidir(
                Optional.of(RODADA_15), Optional.empty(), false, Instant.parse("2026-10-16T00:00:00Z"));

        assertThat(d.buscar()).isTrue();
    }

    @Test
    void semRodadaSemTentativaBusca() {
        Decisao d = RegraAtualizacao.decidir(
                Optional.empty(), Optional.empty(), false, Instant.parse("2026-10-15T18:00:00Z"));

        assertThat(d.buscar()).isTrue();
    }

    @Test
    void brasilia2230UsaUtc() {
        Decisao d = RegraAtualizacao.decidir(
                Optional.of(RODADA_15),
                Optional.of(Instant.parse("2026-10-16T01:00:00Z")),
                false,
                Instant.parse("2026-10-16T01:30:00Z"));

        assertThat(d.buscar()).isTrue();
    }

    @Test
    void tentativaHa5MinUsaGravada() {
        Instant agora = Instant.parse("2026-10-15T18:00:00Z");
        Instant tentativa = agora.minus(Duration.ofMinutes(5));

        Decisao d = RegraAtualizacao.decidir(Optional.of(RODADA_14), Optional.of(tentativa), false, agora);

        assertThat(d.buscar()).isFalse();
        assertThat(d.desatualizado()).isFalse();
        assertThat(d.validoAte()).isEqualTo(tentativa.plus(Duration.ofMinutes(15)));
    }

    @Test
    void tentativaFalhaHa5MinMarcaDesatualizado() {
        Instant agora = Instant.parse("2026-10-15T18:00:00Z");

        Decisao d = RegraAtualizacao.decidir(
                Optional.of(RODADA_14), Optional.of(agora.minus(Duration.ofMinutes(5))), true, agora);

        assertThat(d.buscar()).isFalse();
        assertThat(d.desatualizado()).isTrue();
    }

    @Test
    void tentativaHaExatos15MinBusca() {
        Instant agora = Instant.parse("2026-10-15T18:00:00Z");

        Decisao d = RegraAtualizacao.decidir(
                Optional.of(RODADA_14), Optional.of(agora.minus(Duration.ofMinutes(15))), false, agora);

        assertThat(d.buscar()).isTrue();
    }

    @Test
    void janelaEnviada() {
        Instant agora = Instant.parse("2026-10-15T18:00:00Z");

        assertThat(RegraAtualizacao.inicioJanela(agora)).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
        assertThat(RegraAtualizacao.inicioJanela(agora).toEpochMilli()).isEqualTo(1792022400000L);
        assertThat(RegraAtualizacao.fimJanela(agora)).isEqualTo(Instant.parse("2026-10-30T00:00:00Z"));
        assertThat(RegraAtualizacao.fimJanela(agora).toEpochMilli()).isEqualTo(1793318400000L);
    }
}

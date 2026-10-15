package br.com.neoviaengenharia.clima.domain.observado;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EstadoHoraTest {

    static final Instant AGORA = Instant.parse("2026-10-15T18:20:00Z");

    static LeituraHora leitura(long estacao) {
        return new LeituraHora(new Estacao(estacao, "Prudentópolis", "mg_h-823"),
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, false);
    }

    private static Instant h(String hora) {
        return Instant.parse("2026-10-15T" + hora + ":00Z");
    }

    private static EstadoHora comDado(String hora, Instant tentativa) {
        return new EstadoHora(h(hora), leitura(3078), tentativa, false, tentativa);
    }

    private static EstadoHora semRegistro(String hora, Instant tentativa) {
        return new EstadoHora(h(hora), null, null, false, tentativa);
    }

    @Test
    void correnteSemTentativaBusca() {
        assertThat(EstadoHora.vazio(h("18:00")).deveBuscar(AGORA)).isTrue();
    }

    @Test
    void correnteComDadoTentadaHa70MinBusca() {
        assertThat(comDado("18:00", h("17:10")).deveBuscar(AGORA)).isTrue();
    }

    @Test
    void recenteComTentativaHa70MinBusca() {
        assertThat(comDado("12:00", h("17:10")).deveBuscar(AGORA)).isTrue();
    }

    @Test
    void recenteComTentativaHa20MinNaoBusca() {
        assertThat(comDado("12:00", h("18:00")).deveBuscar(AGORA)).isFalse();
        assertThat(semRegistro("16:00", h("18:00")).deveBuscar(AGORA)).isFalse();
    }

    @Test
    void tentativaHaExatamente1hNaoBusca() {
        assertThat(comDado("12:00", h("17:20")).deveBuscar(AGORA)).isFalse();
    }

    @Test
    void antigaComDadoEhDefinitiva() {
        EstadoHora e = comDado("11:00", h("11:30"));
        assertThat(e.definitiva(AGORA)).isTrue();
        assertThat(e.deveBuscar(AGORA)).isFalse();
    }

    @Test
    void semDadoEhDefinitiva() {
        EstadoHora e = new EstadoHora(h("05:00"), null, null, true, h("12:00"));
        assertThat(e.definitiva(AGORA)).isTrue();
        assertThat(e.deveBuscar(AGORA)).isFalse();
    }

    @Test
    void antigaNuncaTentadaBusca() {
        assertThat(EstadoHora.vazio(h("02:00")).deveBuscar(AGORA)).isTrue();
    }

    @Test
    void antigaSemRegistroComTentativaVelhaBusca() {
        assertThat(semRegistro("11:00", h("17:10")).deveBuscar(AGORA)).isTrue();
    }

    @Test
    void limiteRecenteAntiga() {
        assertThat(EstadoHora.antiga(h("11:00"), AGORA)).isTrue();
        assertThat(EstadoHora.antiga(h("12:00"), AGORA)).isFalse();
        // fechada exatamente há 6 h ainda é recente
        assertThat(EstadoHora.antiga(h("11:00"), h("18:00"))).isFalse();
        assertThat(EstadoHora.antiga(h("18:00"), AGORA)).isFalse();
    }

    @Test
    void validoAteDefinitivaSemPrazo() {
        assertThat(comDado("11:00", h("11:30")).validoAte(AGORA)).isEmpty();
    }

    @Test
    void validoAteRecente() {
        assertThat(comDado("12:00", h("17:10")).validoAte(AGORA)).contains(h("18:10"));
    }

    @Test
    void validoAteSemTentativaNaoCacheavel() {
        assertThat(EstadoHora.vazio(h("02:00")).validoAte(AGORA)).contains(Instant.MIN);
    }
}

package br.com.neoviaengenharia.clima.domain.previsao;

import static br.com.neoviaengenharia.clima.domain.previsao.PontosTeste.ponto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RodadaPrevisaoTest {

    private static final String R14 = "2026-10-14T00:00:00Z";
    private static final String R15 = "2026-10-15T00:00:00Z";

    @Test
    void rodadaEhOMaiorRunDatetime() {
        List<PontoPrevisao> pontos = List.of(
                ponto("2026-10-15T00:00:00Z", R14), ponto("2026-10-15T01:00:00Z", R15));

        assertThat(RodadaPrevisao.rodadaDe(pontos)).isEqualTo(Instant.parse(R15));
        RodadaPrevisao r = new RodadaPrevisao(1359, Instant.parse(R15), Instant.parse(R15), pontos);
        assertThat(r.pontos()).extracting(PontoPrevisao::rodadaPonto)
                .containsExactly(Instant.parse(R14), Instant.parse(R15));
    }

    @Test
    void rodadaDeListaVaziaFalha() {
        assertThatThrownBy(() -> RodadaPrevisao.rodadaDe(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pontosSaemOrdenados() {
        List<PontoPrevisao> pontos = List.of(
                ponto("2026-10-15T03:00:00Z", R15), ponto("2026-10-15T00:00:00Z", R15), ponto("2026-10-15T06:00:00Z", R15));

        RodadaPrevisao r = new RodadaPrevisao(1359, Instant.parse(R15), Instant.parse(R15), pontos);

        assertThat(r.pontos()).extracting(PontoPrevisao::dataHora).containsExactly(
                Instant.parse("2026-10-15T00:00:00Z"), Instant.parse("2026-10-15T03:00:00Z"),
                Instant.parse("2026-10-15T06:00:00Z"));
    }

    @Test
    void comparacao() {
        Instant r14 = Instant.parse(R14);
        Instant r15 = Instant.parse(R15);

        assertThat(ComparacaoRodada.de(Optional.empty(), r15)).isEqualTo(ComparacaoRodada.NOVA);
        assertThat(ComparacaoRodada.de(Optional.of(r15), r15)).isEqualTo(ComparacaoRodada.MESMA);
        assertThat(ComparacaoRodada.de(Optional.of(r14), r15)).isEqualTo(ComparacaoRodada.NOVA);
        assertThat(ComparacaoRodada.de(Optional.of(r15), r14)).isEqualTo(ComparacaoRodada.ANTIGA);
    }
}

package br.com.neoviaengenharia.clima.domain.observado;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AplicacaoBuscaTest {

    static final Instant AGORA = Instant.parse("2026-10-15T18:00:00Z");
    static final Instant DIA_10 = Instant.parse("2026-10-10T00:00:00Z");

    private static Instant hora(Instant base, int h) {
        return base.plus(Duration.ofHours(h));
    }

    private static LeituraHora leitura(long estacao, String soma) {
        return new LeituraHora(new Estacao(estacao, "E" + estacao, "c" + estacao),
                new BigDecimal(soma), new BigDecimal(soma), null, null, null, false);
    }

    private static Map<Instant, EstadoHora> vazios(Set<Instant> horas) {
        Map<Instant, EstadoHora> m = new HashMap<>();
        horas.forEach(h -> m.put(h, EstadoHora.vazio(h)));
        return m;
    }

    private static Set<Instant> dia(Instant base) {
        Set<Instant> s = new LinkedHashSet<>();
        IntStream.range(0, 24).forEach(i -> s.add(hora(base, i)));
        return s;
    }

    @Test
    void gravaRecebidasEMarcaAntigaAusenteComoSemDado() {
        Set<Instant> buscadas = dia(DIA_10);
        List<HoraNimbus> recebidas = buscadas.stream()
                .filter(h -> !h.equals(hora(DIA_10, 5)))
                .map(h -> new HoraNimbus(h, leitura(3078, "0")))
                .toList();

        Map<Instant, EstadoHora> alterados = AplicacaoBusca.aplicar(vazios(buscadas), buscadas, recebidas, AGORA);

        assertThat(alterados).hasSize(24);
        assertThat(alterados.values()).allSatisfy(e -> assertThat(e.ultimaTentativaEm()).isEqualTo(AGORA));
        assertThat(alterados.values()).filteredOn(EstadoHora::temDado).hasSize(23)
                .allSatisfy(e -> assertThat(e.obtidoEm()).isEqualTo(AGORA));
        EstadoHora ausente = alterados.get(hora(DIA_10, 5));
        assertThat(ausente.semDado()).isTrue();
        assertThat(ausente.leitura()).isNull();
    }

    @Test
    void recenteAusenteNaoViraSemDado() {
        Instant h16 = Instant.parse("2026-10-15T16:00:00Z");
        Map<Instant, EstadoHora> alterados = AplicacaoBusca.aplicar(
                vazios(Set.of(h16)), Set.of(h16), List.of(), AGORA);

        EstadoHora e = alterados.get(h16);
        assertThat(e.semDado()).isFalse();
        assertThat(e.temDado()).isFalse();
        assertThat(e.ultimaTentativaEm()).isEqualTo(AGORA);
    }

    @Test
    void recenteAusenteMantemLeituraAnterior() {
        Instant h16 = Instant.parse("2026-10-15T16:00:00Z");
        Instant antes = Instant.parse("2026-10-15T16:30:00Z");
        EstadoHora gravado = new EstadoHora(h16, leitura(3078, "1.5"), antes, false, antes);

        EstadoHora e = AplicacaoBusca.aplicar(Map.of(h16, gravado), Set.of(h16), List.of(), AGORA).get(h16);

        assertThat(e.leitura()).isEqualTo(leitura(3078, "1.5"));
        assertThat(e.obtidoEm()).isEqualTo(antes);
        assertThat(e.ultimaTentativaEm()).isEqualTo(AGORA);
    }

    @Test
    void recebidaSubstituiGravada() {
        Instant h12 = Instant.parse("2026-10-15T12:00:00Z");
        Instant antes = Instant.parse("2026-10-15T13:10:00Z");
        EstadoHora gravado = new EstadoHora(h12, leitura(3078, "1"), antes, false, antes);

        EstadoHora e = AplicacaoBusca.aplicar(Map.of(h12, gravado), Set.of(h12),
                List.of(new HoraNimbus(h12, leitura(3078, "2.5"))), AGORA).get(h12);

        assertThat(e.leitura().precipitacaoAcumulada()).isEqualTo(new BigDecimal("2.5"));
        assertThat(e.obtidoEm()).isEqualTo(AGORA);
    }

    @Test
    void ignoraHorasNaoPedidas() {
        Instant h12 = Instant.parse("2026-10-15T12:00:00Z");
        Instant h13 = Instant.parse("2026-10-15T13:00:00Z");
        Instant h14 = Instant.parse("2026-10-15T14:00:00Z");
        Instant quebrada = Instant.parse("2026-10-15T14:30:00Z");
        Instant fora = Instant.parse("2026-10-15T20:00:00Z");
        EstadoHora definitiva13 = new EstadoHora(h13, leitura(1, "9"), h13, false, h13);
        Map<Instant, EstadoHora> atuais = new HashMap<>(vazios(Set.of(h12, h14)));
        atuais.put(h13, definitiva13);

        Map<Instant, EstadoHora> alterados = AplicacaoBusca.aplicar(atuais, Set.of(h12, h14), List.of(
                new HoraNimbus(h12, leitura(1, "1")),
                new HoraNimbus(h13, leitura(1, "7")),
                new HoraNimbus(quebrada, leitura(1, "7")),
                new HoraNimbus(fora, leitura(1, "7")),
                new HoraNimbus(h14, leitura(1, "3")),
                new HoraNimbus(h14, leitura(1, "4"))), AGORA);

        assertThat(alterados).containsOnlyKeys(h12, h14);
        assertThat(alterados.get(h14).leitura().precipitacaoAcumulada()).isEqualTo(new BigDecimal("4"));
    }

    @Test
    void estacaoPorHora() {
        Set<Instant> buscadas = dia(DIA_10);
        List<HoraNimbus> recebidas = buscadas.stream()
                .map(h -> new HoraNimbus(h, leitura(h.isBefore(hora(DIA_10, 12)) ? 3078 : 3090, "0")))
                .toList();

        Map<Instant, EstadoHora> alterados = AplicacaoBusca.aplicar(vazios(buscadas), buscadas, recebidas, AGORA);

        assertThat(alterados.get(hora(DIA_10, 11)).leitura().estacao().id()).isEqualTo(3078);
        assertThat(alterados.get(hora(DIA_10, 12)).leitura().estacao().id()).isEqualTo(3090);
    }
}

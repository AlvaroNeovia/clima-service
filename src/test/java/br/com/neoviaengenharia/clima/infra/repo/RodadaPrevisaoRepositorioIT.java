package br.com.neoviaengenharia.clima.infra.repo;

import static br.com.neoviaengenharia.clima.domain.previsao.PontosTeste.ponto;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.domain.previsao.PontoPrevisao;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaPrevisao;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaResumo;
import br.com.neoviaengenharia.clima.domain.regiao.Regiao;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoNimbus;
import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RodadaPrevisaoRepositorioIT extends IntegracaoBase {

    private static final String R14 = "2026-10-14T00:00:00Z";
    private static final String R15 = "2026-10-15T00:00:00Z";

    @Autowired
    RodadaPrevisaoRepositorio repositorio;

    @Autowired
    RegiaoRepository regioes;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void limpar() {
        PrevisaoTabelas.limparComRegioes(jdbc);
        Instant agora = Instant.parse("2026-10-15T12:00:00Z");
        regioes.save(Regiao.nova(new RegiaoNimbus(1359, "Franca", "f", false, 25), agora));
        regioes.save(Regiao.nova(new RegiaoNimbus(1706, "Votorantim", "v", false, 25), agora));
    }

    private static RodadaPrevisao rodada(String run, String obtidoEm, PontoPrevisao... pontos) {
        return new RodadaPrevisao(1359, Instant.parse(run), Instant.parse(obtidoEm), List.of(pontos));
    }

    private int contar(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @AfterEach
    void limparDepois() {
        PrevisaoTabelas.limparComRegioes(jdbc);
    }

    @Test
    void gravaELeVigente() {
        repositorio.gravar(rodada(R15, "2026-10-15T03:12:44Z",
                ponto("2026-10-15T02:00:00Z", R15, "temperature", 30),
                ponto("2026-10-15T00:00:00Z", R15, "temperature", 35, "fog_fsi", null),
                ponto("2026-10-15T01:00:00Z", R15, "temperature", 18.14)));

        RodadaPrevisao r = repositorio.vigente(1359).orElseThrow();

        assertThat(r.rodada()).isEqualTo(Instant.parse(R15));
        assertThat(r.obtidoEm()).isEqualTo(Instant.parse("2026-10-15T03:12:44Z"));
        assertThat(r.pontos()).extracting(PontoPrevisao::dataHora).containsExactly(
                Instant.parse("2026-10-15T00:00:00Z"), Instant.parse("2026-10-15T01:00:00Z"),
                Instant.parse("2026-10-15T02:00:00Z"));
        assertThat(r.pontos().get(0).variaveis()).containsEntry("temperature", 35).containsEntry("fog_fsi", null);
        assertThat(r.pontos().get(1).variaveis()).containsEntry("temperature", 18.14);
        assertThat(repositorio.vigente(1706)).isEmpty();
    }

    @Test
    void vigenteEhAMaisRecente() {
        repositorio.gravar(rodada(R14, R14, ponto("2026-10-14T00:00:00Z", R14)));
        repositorio.gravar(rodada(R15, R15, ponto("2026-10-15T00:00:00Z", R15)));

        assertThat(repositorio.vigente(1359).orElseThrow().rodada()).isEqualTo(Instant.parse(R15));
        assertThat(contar("SELECT COUNT(*) FROM clima.rodada_previsao")).isEqualTo(2);
    }

    @Test
    void sobrescreveMesmaRodada() {
        repositorio.gravar(rodada(R15, "2026-10-15T03:00:00Z", ponto("2026-10-15T15:00:00Z", R15, "temperature", 35)));
        repositorio.gravar(rodada(R15, "2026-10-15T09:00:00Z", ponto("2026-10-15T15:00:00Z", R15, "temperature", 36)));

        RodadaPrevisao r = repositorio.vigente(1359).orElseThrow();
        assertThat(contar("SELECT COUNT(*) FROM clima.rodada_previsao")).isEqualTo(1);
        assertThat(r.obtidoEm()).isEqualTo(Instant.parse("2026-10-15T09:00:00Z"));
        assertThat(r.pontos()).singleElement().satisfies(p -> assertThat(p.variaveis()).containsEntry("temperature", 36));
    }

    @Test
    void sobrescreveTrocaTodosOsPontos() {
        repositorio.gravar(rodada(R15, R15,
                ponto("2026-10-15T00:00:00Z", R15), ponto("2026-10-15T01:00:00Z", R15),
                ponto("2026-10-15T02:00:00Z", R15), ponto("2026-10-15T03:00:00Z", R15),
                ponto("2026-10-15T04:00:00Z", R15)));
        repositorio.gravar(rodada(R15, R15,
                ponto("2026-10-15T01:00:00Z", R15, "temperature", 1), ponto("2026-10-15T07:00:00Z", R15)));

        assertThat(repositorio.vigente(1359).orElseThrow().pontos()).extracting(PontoPrevisao::dataHora)
                .containsExactly(Instant.parse("2026-10-15T01:00:00Z"), Instant.parse("2026-10-15T07:00:00Z"));
        assertThat(contar("SELECT COUNT(*) FROM clima.ponto_previsao")).isEqualTo(2);
    }

    @Test
    void instantesEmUtc() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"));
            repositorio.gravar(rodada(R15, "2026-10-15T03:12:44Z",
                    ponto("2026-10-15T00:00:00Z", R15), ponto("2026-10-15T01:00:00Z", R15)));

            RodadaPrevisao r = repositorio.vigente(1359).orElseThrow();
            assertThat(r.rodada()).isEqualTo(Instant.parse(R15));
            assertThat(r.obtidoEm()).isEqualTo(Instant.parse("2026-10-15T03:12:44Z"));
            assertThat(r.pontos().get(0).dataHora()).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
            assertThat(r.pontos().get(0).rodadaPonto()).isEqualTo(Instant.parse(R15));
        } finally {
            TimeZone.setDefault(original);
        }
        assertThat(contar("SELECT COUNT(*) FROM clima.ponto_previsao p JOIN clima.rodada_previsao r "
                + "ON p.rodada_id = r.id WHERE p.run_datetime = r.run_datetime")).isEqualTo(2);
        assertThat(contar("SELECT COUNT(*) FROM clima.rodada_previsao "
                + "WHERE run_datetime = CAST('2026-10-15 00:00:00' AS DATETIME2)")).isEqualTo(1);
    }

    @Test
    void buscarRodadaExata() {
        repositorio.gravar(rodada(R14, R14, ponto("2026-10-14T00:00:00Z", R14)));

        assertThat(repositorio.buscar(1359, Instant.parse(R14))).isPresent();
        assertThat(repositorio.buscar(1359, Instant.parse("2026-10-14T01:00:00Z"))).isEmpty();
        assertThat(repositorio.buscar(1706, Instant.parse(R14))).isEmpty();
    }

    @Test
    void listarNoPeriodoDecrescente() {
        for (int dia = 10; dia <= 15; dia++) {
            String run = String.format("2026-10-%02dT00:00:00Z", dia);
            repositorio.gravar(rodada(run, run, ponto(run.replace("00:00:00Z", "12:00:00Z"), run)));
        }

        List<RodadaResumo> rodadas = repositorio.listar(1359, Instant.parse("2026-10-12T00:00:00Z"),
                Instant.parse("2026-10-14T00:00:00Z"));

        assertThat(rodadas).extracting(RodadaResumo::rodada).containsExactly(
                Instant.parse("2026-10-14T00:00:00Z"), Instant.parse("2026-10-13T00:00:00Z"),
                Instant.parse("2026-10-12T00:00:00Z"));
        assertThat(rodadas.get(0).obtidoEm()).isEqualTo(Instant.parse("2026-10-14T00:00:00Z"));
        assertThat(repositorio.listar(1706, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-31T00:00:00Z")))
                .isEmpty();
        assertThat(repositorio.listar(1359, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-30T00:00:00Z")))
                .isEmpty();
    }
}

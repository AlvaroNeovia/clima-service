package br.com.neoviaengenharia.clima.app.previsao;

import static br.com.neoviaengenharia.clima.app.previsao.PrevisaoFixture.ativa;
import static br.com.neoviaengenharia.clima.app.previsao.PrevisaoFixture.inativa;
import static br.com.neoviaengenharia.clima.app.previsao.PrevisaoFixture.regiao;
import static br.com.neoviaengenharia.clima.app.previsao.PrevisaoFixture.resposta;
import static br.com.neoviaengenharia.clima.domain.previsao.PontosTeste.ponto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.neoviaengenharia.clima.domain.previsao.PontoPrevisao;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaResumo;
import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class PrevisaoHistoricoServiceIT extends IntegracaoBase {

    static final Instant AGORA = Instant.parse("2026-10-15T18:00:00Z");
    static final String R14 = "2026-10-14T00:00:00Z";
    static final String R15 = "2026-10-15T00:00:00Z";

    @Autowired
    PrevisaoHistoricoService service;

    @Autowired
    PrevisaoVigenteService vigente;

    @Autowired
    ApplicationContext ctx;

    @Autowired
    JdbcTemplate jdbc;

    PrevisaoFixture fx;
    int requisicoesAntes;

    @BeforeEach
    void preparar() throws InterruptedException {
        relogio.definir(AGORA);
        fx = new PrevisaoFixture(ctx);
        fx.limpar(nimbus);
        fx.semearCatalogo(AGORA, ativa(1359), inativa(1706), ativa(1800));
        fx.gravarRodada(1359, R14, "2026-10-14T03:00:00Z", ponto("2026-10-14T15:00:00Z", R14, "temperature", 30));
        fx.gravarRodada(1359, R15, "2026-10-15T03:00:00Z", ponto("2026-10-15T15:00:00Z", R15, "temperature", 35));
        jdbc.update("UPDATE clima.regiao SET removida = TRUE WHERE region_id = 1800");
        requisicoesAntes = nimbus.getRequestCount();
    }

    @AfterEach
    void limparDepois() throws InterruptedException {
        fx.limpar(nimbus);
    }

    @Test
    void listagemNoPeriodo() {
        List<RodadaResumo> rodadas = service.listar(1359, Instant.parse(R14), Instant.parse(R15));

        assertThat(rodadas).extracting(RodadaResumo::rodada).containsExactly(Instant.parse(R15), Instant.parse(R14));
        assertThat(rodadas.get(0).obtidoEm()).isEqualTo(Instant.parse("2026-10-15T03:00:00Z"));
        assertThat(novasRequisicoes()).isZero();
    }

    @Test
    void periodoSemRodadas() {
        assertThat(service.listar(1359, Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z")))
                .isEmpty();
    }

    @Test
    void regiaoInexistenteNaListagem() {
        assertThatThrownBy(() -> service.listar(99999, Instant.parse(R14), Instant.parse(R15)))
                .isInstanceOf(RegiaoNaoEncontradaException.class);
    }

    @Test
    void rodadaEspecifica() {
        ItemPrevisao item = service.rodada(1359, Instant.parse(R14), null, null);

        assertThat(item.regionId()).isEqualTo(1359);
        assertThat(item.erro()).isNull();
        assertThat(item.desatualizado()).isFalse();
        assertThat(item.rodada().rodada()).isEqualTo(Instant.parse(R14));
        assertThat(item.rodada().obtidoEm()).isEqualTo(Instant.parse("2026-10-14T03:00:00Z"));
        assertThat(item.rodada().pontos()).singleElement()
                .satisfies(p -> assertThat(p.variaveis()).containsEntry("temperature", 30));
        assertThat(novasRequisicoes()).isZero();
    }

    @Test
    void filtroNaRodada() {
        fx.gravarRodada(1359, R14, "2026-10-14T03:00:00Z", ponto("2026-10-14T00:00:00Z", R14),
                ponto("2026-10-14T06:00:00Z", R14), ponto("2026-10-14T07:00:00Z", R14), ponto("2026-10-14T08:00:00Z", R14),
                ponto("2026-10-14T09:00:00Z", R14));

        ItemPrevisao item = service.rodada(1359, Instant.parse(R14), Instant.parse("2026-10-14T06:00:00Z"),
                Instant.parse("2026-10-14T08:00:00Z"));

        assertThat(item.rodada().pontos()).extracting(PontoPrevisao::dataHora).containsExactly(
                Instant.parse("2026-10-14T06:00:00Z"), Instant.parse("2026-10-14T07:00:00Z"),
                Instant.parse("2026-10-14T08:00:00Z"));
        assertThat(novasRequisicoes()).isZero();
    }

    @Test
    void rodadaInexistente() {
        assertThatThrownBy(() -> service.rodada(1359, Instant.parse("2026-10-14T01:00:00Z"), null, null))
                .isInstanceOf(RodadaNaoEncontradaException.class);
    }

    @Test
    void regiaoInexistenteNaRodada() {
        assertThatThrownBy(() -> service.rodada(99999, Instant.parse(R14), null, null))
                .isInstanceOf(RegiaoNaoEncontradaException.class);
    }

    @Test
    void inativaERemovidaComHistorico() {
        fx.gravarRodada(1706, R14, "2026-10-14T03:00:00Z", ponto("2026-10-14T15:00:00Z", R14, "temperature", 28));
        fx.gravarRodada(1800, R14, "2026-10-14T03:00:00Z", ponto("2026-10-14T15:00:00Z", R14, "temperature", 26));

        assertThat(service.listar(1706, Instant.parse(R14), Instant.parse(R14))).hasSize(1);
        assertThat(service.listar(1800, Instant.parse(R14), Instant.parse(R14))).hasSize(1);
        assertThat(service.rodada(1706, Instant.parse(R14), null, null).rodada().pontos()).hasSize(1);
        assertThat(service.rodada(1800, Instant.parse(R14), null, null).rodada().pontos()).hasSize(1);
        assertThat(novasRequisicoes()).isZero();
    }

    @Test
    void segundaLeituraVemDoCache() {
        assertThat(service.rodada(1359, Instant.parse(R14), null, null).rodada().pontos()).hasSize(1);
        jdbc.update("DELETE FROM clima.ponto_previsao");

        assertThat(service.rodada(1359, Instant.parse(R14), null, null).rodada().pontos()).hasSize(1);
    }

    @Test
    void sobrescritaInvalidaCache() throws Exception {
        assertThat(service.rodada(1359, Instant.parse(R15), null, null).rodada().pontos().get(0).variaveis())
                .containsEntry("temperature", 35);
        relogio.definir(Instant.parse("2026-10-16T01:30:00Z"));
        fx.registrarTentativa(1359, Instant.parse("2026-10-16T01:00:00Z"), false);
        nimbus.enqueue(resposta(regiao(1359, R15, "\"temperature\":36", "2026-10-15T15:00:00Z")));

        vigente.consultar(List.of(1359L), null, null);
        ItemPrevisao depois = service.rodada(1359, Instant.parse(R15), null, null);

        assertThat(depois.rodada().pontos().get(0).variaveis()).containsEntry("temperature", 36);
    }

    int novasRequisicoes() {
        return nimbus.getRequestCount() - requisicoesAntes;
    }
}
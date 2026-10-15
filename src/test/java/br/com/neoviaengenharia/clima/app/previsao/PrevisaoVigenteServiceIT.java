package br.com.neoviaengenharia.clima.app.previsao;

import static br.com.neoviaengenharia.clima.app.previsao.PrevisaoFixture.ativa;
import static br.com.neoviaengenharia.clima.app.previsao.PrevisaoFixture.corpo;
import static br.com.neoviaengenharia.clima.app.previsao.PrevisaoFixture.horarios;
import static br.com.neoviaengenharia.clima.app.previsao.PrevisaoFixture.inativa;
import static br.com.neoviaengenharia.clima.app.previsao.PrevisaoFixture.regiao;
import static br.com.neoviaengenharia.clima.app.previsao.PrevisaoFixture.resposta;
import static br.com.neoviaengenharia.clima.app.previsao.PrevisaoFixture.semDado;
import static br.com.neoviaengenharia.clima.domain.previsao.PontosTeste.ponto;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.domain.previsao.PontoPrevisao;
import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class PrevisaoVigenteServiceIT extends IntegracaoBase {

    static final Instant AGORA = Instant.parse("2026-10-15T18:00:00Z");
    static final String R14 = "2026-10-14T00:00:00Z";
    static final String R15 = "2026-10-15T00:00:00Z";
    static final String R16 = "2026-10-16T00:00:00Z";

    @Autowired
    PrevisaoVigenteService service;

    @Autowired
    ApplicationContext ctx;

    @Autowired
    ObjectMapper mapper;

    PrevisaoFixture fx;
    int requisicoesAntes;

    @BeforeEach
    void preparar() throws InterruptedException {
        relogio.definir(AGORA);
        fx = new PrevisaoFixture(ctx);
        fx.limpar(nimbus);
        fx.semearCatalogo(AGORA, ativa(1359), ativa(1706), ativa(1710), inativa(1720));
        requisicoesAntes = nimbus.getRequestCount();
    }

    @AfterEach
    void limparDepois() throws InterruptedException {
        fx.limpar(nimbus);
    }

    int novasRequisicoes() {
        return nimbus.getRequestCount() - requisicoesAntes;
    }

    JsonNode ultimoCorpo() throws Exception {
        RecordedRequest req = nimbus.takeRequest(1, TimeUnit.SECONDS);
        return mapper.readTree(corpo(req));
    }

    static List<Long> regioesDoCorpo(JsonNode corpo) {
        return Stream.of(corpo.get("regions")).flatMap(r -> {
            List<Long> ids = new java.util.ArrayList<>();
            r.forEach(n -> ids.add(n.get("region_id").asLong()));
            return ids.stream();
        }).toList();
    }

    ItemPrevisao unico(List<Long> ids) {
        return service.consultar(ids, null, null).itens().get(0);
    }

    @Test
    void rodadaDoDiaNaoChama() {
        fx.gravarRodada(1359, R15, "2026-10-15T03:12:44Z", ponto("2026-10-15T15:00:00Z", R15, "temperature", 35));

        ItemPrevisao item = unico(List.of(1359L));

        assertThat(novasRequisicoes()).isZero();
        assertThat(item.erro()).isNull();
        assertThat(item.desatualizado()).isFalse();
        assertThat(item.rodada().rodada()).isEqualTo(Instant.parse(R15));
        assertThat(item.rodada().obtidoEm()).isEqualTo(Instant.parse("2026-10-15T03:12:44Z"));
    }

    @Test
    void primeiraConsultaBuscaEGrava() throws Exception {
        nimbus.enqueue(resposta(regiao(1359, R15, "\"temperature\":35", "2026-10-15T15:00:00Z", "2026-10-15T16:00:00Z")));

        ItemPrevisao item = unico(List.of(1359L));

        assertThat(novasRequisicoes()).isEqualTo(1);
        JsonNode corpo = ultimoCorpo();
        assertThat(corpo.get("datetime_start").asLong()).isEqualTo(1792022400000L);
        assertThat(corpo.get("datetime_end").asLong()).isEqualTo(1793318400000L);
        assertThat(corpo.get("frequency").asLong()).isEqualTo(3600000L);
        assertThat(corpo.get("integration").asBoolean()).isTrue();
        assertThat(item.rodada().rodada()).isEqualTo(Instant.parse(R15));
        assertThat(item.rodada().obtidoEm()).isEqualTo(AGORA);
        assertThat(item.rodada().pontos()).hasSize(2);
        assertThat(fx.rodadas.vigente(1359)).isPresent();
        assertThat(fx.tentativa(1359).getUltimaTentativaEm()).isEqualTo(AGORA);
        assertThat(fx.tentativa(1359).isUltimaFalhou()).isFalse();

        relogio.avancar(Duration.ofHours(2));
        assertThat(unico(List.of(1359L)).rodada().pontos()).hasSize(2);
        assertThat(novasRequisicoes()).isEqualTo(1);
    }

    @Test
    void utcNaoLocal() throws Exception {
        fx.gravarRodada(1359, R15, R15, ponto("2026-10-15T15:00:00Z", R15));
        fx.registrarTentativa(1359, Instant.parse("2026-10-16T01:00:00Z"), false);
        relogio.definir(Instant.parse("2026-10-16T01:30:00Z"));
        nimbus.enqueue(resposta(regiao(1359, R16, "", "2026-10-16T03:00:00Z")));

        ItemPrevisao item = unico(List.of(1359L));

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(ultimoCorpo().get("datetime_start").asLong())
                .isEqualTo(Instant.parse("2026-10-16T00:00:00Z").toEpochMilli());
        assertThat(item.rodada().rodada()).isEqualTo(Instant.parse(R16));
    }

    @Test
    void reconsultaRespeita15Min() {
        fx.gravarRodada(1359, R14, R14, ponto("2026-10-14T15:00:00Z", R14));
        fx.registrarTentativa(1359, AGORA.minus(Duration.ofMinutes(5)), false);

        ItemPrevisao item = unico(List.of(1359L));

        assertThat(novasRequisicoes()).isZero();
        assertThat(item.desatualizado()).isFalse();
        assertThat(item.rodada().rodada()).isEqualTo(Instant.parse(R14));
    }

    @Test
    void rodadaNovaPreservaHistorico() {
        fx.gravarRodada(1359, R14, R14, ponto("2026-10-14T15:00:00Z", R14));
        nimbus.enqueue(resposta(regiao(1359, R15, "", "2026-10-15T15:00:00Z")));

        ItemPrevisao item = unico(List.of(1359L));

        assertThat(fx.contarRodadas()).isEqualTo(2);
        assertThat(item.rodada().rodada()).isEqualTo(Instant.parse(R15));
    }

    @Test
    void mesmaRodadaSobrescreveEInvalidaCacheDoS3() {
        fx.gravarRodada(1359, R15, "2026-10-15T03:00:00Z", ponto("2026-10-15T15:00:00Z", R15, "temperature", 35));
        fx.registrarTentativa(1359, Instant.parse("2026-10-15T03:00:00Z"), false);
        fx.semearChaveRedis("clima:previsao:rodada:1359:1792022400000");
        relogio.definir(Instant.parse("2026-10-16T01:30:00Z"));
        nimbus.enqueue(resposta(regiao(1359, R15, "\"temperature\":36", "2026-10-15T15:00:00Z")));

        ItemPrevisao item = unico(List.of(1359L));

        assertThat(fx.contarRodadas()).isEqualTo(1);
        assertThat(item.rodada().obtidoEm()).isEqualTo(Instant.parse("2026-10-16T01:30:00Z"));
        assertThat(item.rodada().pontos().get(0).variaveis()).containsEntry("temperature", 36);
        assertThat(fx.existeChaveRedis("clima:previsao:rodada:1359:1792022400000")).isFalse();
    }

    @Test
    void rodadaMaisAntigaMantemVigente() {
        fx.gravarRodada(1359, R15, R15, ponto("2026-10-15T15:00:00Z", R15));
        relogio.definir(Instant.parse("2026-10-16T01:30:00Z"));
        nimbus.enqueue(resposta(regiao(1359, R14, "", "2026-10-14T15:00:00Z")));

        ItemPrevisao item = unico(List.of(1359L));

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(item.rodada().rodada()).isEqualTo(Instant.parse(R15));
        assertThat(item.desatualizado()).isFalse();
        assertThat(fx.contarRodadas()).isEqualTo(1);
        assertThat(fx.tentativa(1359).getUltimaTentativaEm()).isEqualTo(Instant.parse("2026-10-16T01:30:00Z"));
    }

    @Test
    void variasRegioesNumaChamada() throws Exception {
        fx.gravarRodada(1359, R15, R15, ponto("2026-10-15T15:00:00Z", R15));
        nimbus.enqueue(resposta(regiao(1706, R15, "", "2026-10-15T15:00:00Z"), regiao(1710, R15, "", "2026-10-15T15:00:00Z")));

        List<ItemPrevisao> itens = service.consultar(List.of(1359L, 1706L, 1710L), null, null).itens();

        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(regioesDoCorpo(ultimoCorpo())).containsExactly(1706L, 1710L);
        assertThat(itens).extracting(ItemPrevisao::regionId).containsExactly(1359L, 1706L, 1710L);
        assertThat(itens).allSatisfy(i -> assertThat(i.rodada()).isNotNull());
    }

    @Test
    void erroPorRegiaoNaOrdem() throws Exception {
        nimbus.enqueue(resposta(regiao(1359, R15, "", "2026-10-15T15:00:00Z")));

        List<ItemPrevisao> itens = service.consultar(List.of(1359L, 1720L, 99999L), null, null).itens();

        assertThat(itens).extracting(ItemPrevisao::regionId).containsExactly(1359L, 1720L, 99999L);
        assertThat(itens.get(0).rodada().pontos()).hasSize(1);
        assertThat(itens.get(0).erro()).isNull();
        assertThat(itens.get(1).erro()).isEqualTo(CodigoErroRegiao.INATIVA);
        assertThat(itens.get(1).rodada()).isNull();
        assertThat(itens.get(2).erro()).isEqualTo(CodigoErroRegiao.NAO_ENCONTRADA);
        assertThat(regioesDoCorpo(ultimoCorpo())).containsExactly(1359L);
    }

    @Test
    void regiaoSemDado() {
        nimbus.enqueue(resposta(semDado(1706)));

        ItemPrevisao item = unico(List.of(1706L));

        assertThat(item.rodada()).isNull();
        assertThat(item.erro()).isNull();
        assertThat(item.desatualizado()).isFalse();
        assertThat(fx.tentativa(1706).getUltimaTentativaEm()).isEqualTo(AGORA);
        assertThat(fx.tentativa(1706).isUltimaFalhou()).isFalse();

        relogio.avancar(Duration.ofMinutes(5));
        ItemPrevisao denovo = unico(List.of(1706L));
        assertThat(novasRequisicoes()).isEqualTo(1);
        assertThat(denovo.rodada()).isNull();
        assertThat(denovo.erro()).isNull();
    }

    @Test
    void filtroDeJanelaSemChamada() {
        PontoPrevisao[] pontos = Stream.of(horarios("2026-10-15T00:00:00Z", "2026-10-17T00:00:00Z"))
                .map(d -> ponto(d, R15)).toArray(PontoPrevisao[]::new);
        fx.gravarRodada(1359, R15, R15, pontos);

        ItemPrevisao item = service.consultar(List.of(1359L),
                Instant.parse("2026-10-16T00:00:00Z"), Instant.parse("2026-10-16T05:00:00Z")).itens().get(0);

        assertThat(novasRequisicoes()).isZero();
        assertThat(item.rodada().pontos()).hasSize(6);
    }
}

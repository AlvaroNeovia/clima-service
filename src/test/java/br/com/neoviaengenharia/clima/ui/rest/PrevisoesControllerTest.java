package br.com.neoviaengenharia.clima.ui.rest;

import static br.com.neoviaengenharia.clima.domain.previsao.PontosTeste.ponto;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.neoviaengenharia.clima.app.previsao.CodigoErroRegiao;
import br.com.neoviaengenharia.clima.app.previsao.ConsultaPrevisao;
import br.com.neoviaengenharia.clima.app.previsao.ItemPrevisao;
import br.com.neoviaengenharia.clima.app.previsao.PrevisaoVigenteService;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaPrevisao;
import br.com.neoviaengenharia.clima.ui.exceptions.ExceptionManager;
import br.com.neoviaengenharia.clima.ui.exceptions.PrevisaoExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PrevisoesController.class)
@Import({ExceptionManager.class, PrevisaoExceptionHandler.class})
class PrevisoesControllerTest {

    private static final String R15 = "2026-10-15T00:00:00Z";

    /** Spec S2 §5.1, com o ponto de exemplo. */
    private static final String CORPO = """
            {"regioes":[
              {"regionId":1359,"rodada":"2026-10-15T00:00:00Z","obtidoEm":"2026-10-15T03:12:44Z","desatualizado":false,
               "erro":null,
               "pontos":[{"dataHora":"2026-10-15T15:00:00Z","rodadaPonto":"2026-10-15T00:00:00Z",
                 "variaveis":{"direcaoVento":297,"temperatura":35,"rajadaVento":18.14,"velocidadeVento":10.8,
                   "umidadeRelativa":24.19,"precipitacao":0.2,"nevoeiroFsi":null,"nevoeiroVisibilidade":5}}]},
              {"regionId":1706,"rodada":null,"obtidoEm":null,"desatualizado":false,
               "erro":{"codigo":"INATIVA","mensagem":"Região inativa"},"pontos":[]}
            ]}""";

    @Autowired
    MockMvc mvc;

    @MockBean
    PrevisaoVigenteService service;

    private static ItemPrevisao itemOk() {
        RodadaPrevisao r = new RodadaPrevisao(1359, Instant.parse(R15), Instant.parse("2026-10-15T03:12:44Z"),
                List.of(ponto("2026-10-15T15:00:00Z", R15, "wind_direction", 297, "temperature", 35, "wind_gust", 18.14,
                        "wind_speed", 10.8, "relative_humidity", 24.19, "precipitation", 0.2, "fog_fsi", null,
                        "fog_vis", 5)));
        return new ItemPrevisao(1359, r, false, null);
    }

    @Test
    void formatoDaSpec() throws Exception {
        when(service.consultar(List.of(1359L, 1706L), null, null)).thenReturn(new ConsultaPrevisao(List.of(
                itemOk(), new ItemPrevisao(1706, null, false, CodigoErroRegiao.INATIVA))));

        mvc.perform(get("/api/clima/previsoes").param("regioes", "1359,1706"))
                .andExpect(status().isOk())
                .andExpect(content().json(CORPO, true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"nevoeiroFsi\":null")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fog_fsi"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1,1", "1,abc", "1,,2", "-5", "1.5", "99999999999999999999"})
    void regioesInvalidas400(String regioes) throws Exception {
        mvc.perform(get("/api/clima/previsoes").param("regioes", regioes))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Parâmetros inválidos: ")));
        verifyNoInteractions(service);
    }

    @Test
    void regioesAusente400() throws Exception {
        mvc.perform(get("/api/clima/previsoes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parâmetros inválidos: regioes é obrigatório"));
    }

    @Test
    void maisDe20Regioes400() throws Exception {
        String ids = LongStream.rangeClosed(1, 21).mapToObj(Long::toString).collect(Collectors.joining(","));

        mvc.perform(get("/api/clima/previsoes").param("regioes", ids))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Parâmetros inválidos: ")));
    }

    @Test
    void vinteRegioesAceitas() throws Exception {
        String ids = LongStream.rangeClosed(1, 20).mapToObj(Long::toString).collect(Collectors.joining(","));
        when(service.consultar(any(), any(), any())).thenReturn(new ConsultaPrevisao(List.of()));

        mvc.perform(get("/api/clima/previsoes").param("regioes", ids)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"inicio=2026-10-15T00:00:00", "inicio=lixo", "fim=2026-10-15",
            "inicio=2026-10-16T00:00:00Z&fim=2026-10-15T00:00:00Z"})
    void datasInvalidas400(String query) throws Exception {
        mvc.perform(get("/api/clima/previsoes?regioes=1359&" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Parâmetros inválidos: ")));
        verifyNoInteractions(service);
    }

    @Test
    void datasComOffsetAceitas() throws Exception {
        when(service.consultar(any(), any(), any())).thenReturn(new ConsultaPrevisao(List.of(itemOk())));

        mvc.perform(get("/api/clima/previsoes").param("regioes", "1359")
                        .param("inicio", "2026-10-15T00:00:00-03:00").param("fim", "2026-10-15T03:00:00Z"))
                .andExpect(status().isOk());

        verify(service).consultar(List.of(1359L), Instant.parse("2026-10-15T03:00:00Z"),
                Instant.parse("2026-10-15T03:00:00Z"));
    }

    @Test
    void todasIndisponiveis503() throws Exception {
        when(service.consultar(any(), any(), any())).thenReturn(new ConsultaPrevisao(List.of(
                new ItemPrevisao(1359, null, false, CodigoErroRegiao.FONTE_INDISPONIVEL),
                new ItemPrevisao(1706, null, false, CodigoErroRegiao.FONTE_INDISPONIVEL))));

        mvc.perform(get("/api/clima/previsoes").param("regioes", "1359,1706"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json(
                        "{\"status\":\"SERVICE_UNAVAILABLE\",\"message\":\"Fonte de dados climáticos indisponível\"}",
                        true));
    }

    @Test
    void todasRecusadas200() throws Exception {
        when(service.consultar(any(), any(), any())).thenReturn(new ConsultaPrevisao(List.of(
                new ItemPrevisao(1720, null, false, CodigoErroRegiao.INATIVA),
                new ItemPrevisao(99999, null, false, CodigoErroRegiao.NAO_ENCONTRADA))));

        mvc.perform(get("/api/clima/previsoes").param("regioes", "1720,99999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regioes[1].erro.codigo").value("NAO_ENCONTRADA"))
                .andExpect(jsonPath("$.regioes[1].erro.mensagem").value("Região não encontrada"));
    }

    @Test
    void ordemPreservada() throws Exception {
        when(service.consultar(any(), any(), any())).thenReturn(new ConsultaPrevisao(List.of()));

        mvc.perform(get("/api/clima/previsoes").param("regioes", "1706, 1359")).andExpect(status().isOk());

        verify(service).consultar(eq(List.of(1706L, 1359L)), isNull(), isNull());
    }
}

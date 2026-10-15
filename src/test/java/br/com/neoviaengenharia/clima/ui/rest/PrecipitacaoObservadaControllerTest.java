package br.com.neoviaengenharia.clima.ui.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.neoviaengenharia.clima.app.observado.ObservadoResultado;
import br.com.neoviaengenharia.clima.app.observado.PrecipitacaoObservadaService;
import br.com.neoviaengenharia.clima.app.observado.RegiaoNaoEncontradaException;
import br.com.neoviaengenharia.clima.app.regiao.FonteIndisponivelException;
import br.com.neoviaengenharia.clima.domain.observado.EstadoHora;
import br.com.neoviaengenharia.clima.domain.observado.Estacao;
import br.com.neoviaengenharia.clima.domain.observado.JanelaObservada;
import br.com.neoviaengenharia.clima.domain.observado.LeituraHora;
import br.com.neoviaengenharia.clima.ui.exceptions.ExceptionManager;
import br.com.neoviaengenharia.clima.ui.exceptions.ObservadoExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PrecipitacaoObservadaController.class)
@Import({ExceptionManager.class, ObservadoExceptionHandler.class})
class PrecipitacaoObservadaControllerTest {

    static final String URL = "/api/clima/precipitacoes-observadas/1674";
    static final Instant H0 = Instant.parse("2026-10-14T00:00:00Z");
    static final JanelaObservada JANELA = new JanelaObservada(H0, Instant.parse("2026-10-15T00:00:00Z"));

    @Autowired
    MockMvc mvc;

    @MockBean
    PrecipitacaoObservadaService service;

    @Test
    void formatoDaResposta() throws Exception {
        EstadoHora hora = new EstadoHora(H0, new LeituraHora(new Estacao(3078, "Prudentópolis", "mg_h-823"),
                new BigDecimal("0.0000"), BigDecimal.ZERO, H0, null, null, false),
                Instant.parse("2026-10-14T07:05:12Z"), false, Instant.parse("2026-10-14T07:05:12Z"));
        when(service.consultar(1674, JANELA)).thenReturn(new ObservadoResultado(1674, JANELA, false, List.of(hora)));

        String corpo = mvc.perform(get(URL).param("inicio", "2026-10-14T00:00:00Z").param("fim", "2026-10-15T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"regionId":1674,"inicio":"2026-10-14T00:00:00Z","fim":"2026-10-15T00:00:00Z",
                         "desatualizado":false,"horas":[{
                           "dataHora":"2026-10-14T00:00:00Z",
                           "estacao":{"id":3078,"nome":"Prudentópolis","codigo":"mg_h-823"},
                           "precipitacaoAcumulada":0,"precipitacaoMaxima":0,
                           "dataHoraMaxima":"2026-10-14T00:00:00Z",
                           "primeiraLeitura":null,"dataHoraPrimeiraLeitura":null,
                           "interpolado":false,"obtidoEm":"2026-10-14T07:05:12Z"}]}""", true))
                .andReturn().getResponse().getContentAsString();

        assertThat(corpo).contains("\"precipitacaoAcumulada\":0,").contains("\"primeiraLeitura\":null");
    }

    @Test
    void desatualizadoNoCorpo() throws Exception {
        when(service.consultar(1674, JANELA)).thenReturn(new ObservadoResultado(1674, JANELA, true, List.of()));

        mvc.perform(get(URL).param("inicio", "2026-10-14T00:00:00Z").param("fim", "2026-10-15T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"desatualizado\":true,\"horas\":[]}"));
    }

    @Test
    void semParametros400() throws Exception {
        mvc.perform(get(URL))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(
                        "{\"status\":\"BAD_REQUEST\",\"message\":\"Parâmetros inválidos: inicio é obrigatório\"}", true));
        verifyNoInteractions(service);
    }

    @Test
    void foraDeHoraCheia400() throws Exception {
        mvc.perform(get(URL).param("inicio", "2026-10-14T00:30:00Z").param("fim", "2026-10-15T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"status\":\"BAD_REQUEST\"}"));
    }

    @Test
    void janelaMaiorQue7Dias400() throws Exception {
        mvc.perform(get(URL).param("inicio", "2026-10-01T00:00:00Z").param("fim", "2026-10-09T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"status\":\"BAD_REQUEST\",\"message\":\"Janela máxima de 7 dias\"}", true));
    }

    @Test
    void regionIdNaoNumerico400() throws Exception {
        mvc.perform(get("/api/clima/precipitacoes-observadas/abc")
                        .param("inicio", "2026-10-14T00:00:00Z").param("fim", "2026-10-15T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(
                        "{\"status\":\"BAD_REQUEST\",\"message\":\"Parâmetros inválidos: regionId deve ser numérico\"}",
                        true));
    }

    @Test
    void regiaoInexistente404() throws Exception {
        when(service.consultar(eq(99999L), any())).thenThrow(new RegiaoNaoEncontradaException());

        mvc.perform(get("/api/clima/precipitacoes-observadas/99999")
                        .param("inicio", "2026-10-14T00:00:00Z").param("fim", "2026-10-15T00:00:00Z"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"status\":\"NOT_FOUND\",\"message\":\"Região não encontrada\"}", true));
    }

    @Test
    void fonteIndisponivel503() throws Exception {
        when(service.consultar(anyLong(), any())).thenThrow(new FonteIndisponivelException());

        mvc.perform(get(URL).param("inicio", "2026-10-14T00:00:00Z").param("fim", "2026-10-15T00:00:00Z"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json(
                        "{\"status\":\"SERVICE_UNAVAILABLE\",\"message\":\"Fonte de dados climáticos indisponível\"}",
                        true));
    }
}

package br.com.neoviaengenharia.clima.ui.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.neoviaengenharia.clima.app.regiao.CatalogoRegioesService;
import br.com.neoviaengenharia.clima.app.regiao.CatalogoResultado;
import br.com.neoviaengenharia.clima.app.regiao.FonteIndisponivelException;
import br.com.neoviaengenharia.clima.app.regiao.MotivoRecusa;
import br.com.neoviaengenharia.clima.domain.regiao.CatalogoSnapshot;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoResumo;
import br.com.neoviaengenharia.clima.ui.exceptions.ExceptionManager;
import br.com.neoviaengenharia.clima.ui.exceptions.RegiaoRecusadaException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(RegioesController.class)
@Import({ExceptionManager.class, RegioesControllerTest.EndpointsDeTeste.class})
@ExtendWith(OutputCaptureExtension.class)
class RegioesControllerTest {

    private static final String CORPO = """
            {"regioes":[
              {"regionId":1359,"nome":"01 SP-334 - Franca","alias":"f_neo_fra","inativa":false,"estadoId":25},
              {"regionId":1706,"nome":"01 Votorantim (Sorocaba)","alias":"f_neo_vot","inativa":false,"estadoId":25}
            ],"obtidoEm":"2026-10-15T12:00:00Z","desatualizado":false}""";

    @Autowired
    MockMvc mvc;

    @MockBean
    CatalogoRegioesService service;

    private static CatalogoResultado catalogo() {
        return new CatalogoResultado(
                new CatalogoSnapshot(
                        List.of(
                                new RegiaoResumo(1359, "01 SP-334 - Franca", "f_neo_fra", false, 25),
                                new RegiaoResumo(1706, "01 Votorantim (Sorocaba)", "f_neo_vot", false, 25)),
                        Instant.parse("2026-10-15T12:00:00Z")),
                false);
    }

    @Test
    void getListaRegioes() throws Exception {
        when(service.obter()).thenReturn(catalogo());

        mvc.perform(get("/api/clima/regioes"))
                .andExpect(status().isOk())
                .andExpect(content().json(CORPO, true));
    }

    @Test
    void postAtualizarMesmoFormato() throws Exception {
        when(service.atualizar()).thenReturn(catalogo());

        mvc.perform(post("/api/clima/regioes/atualizar"))
                .andExpect(status().isOk())
                .andExpect(content().json(CORPO, true));
    }

    @Test
    void fonteIndisponivel503() throws Exception {
        when(service.obter()).thenThrow(new FonteIndisponivelException());

        mvc.perform(get("/api/clima/regioes"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json(
                        "{\"status\":\"SERVICE_UNAVAILABLE\",\"message\":\"Fonte de dados climáticos indisponível\"}",
                        true));
    }

    @Test
    void regiaoRecusada404e422() throws Exception {
        mvc.perform(get("/teste/recusa/nao-encontrada"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"status\":\"NOT_FOUND\",\"message\":\"Região não encontrada\"}", true));
        mvc.perform(get("/teste/recusa/inativa"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json(
                        "{\"status\":\"UNPROCESSABLE_ENTITY\",\"message\":\"Região inativa\"}", true));
    }

    @Test
    void erroInesperado500SemDetalhes(CapturedOutput saida) throws Exception {
        when(service.obter()).thenThrow(new IllegalStateException("segredo-interno-xyz"));

        mvc.perform(get("/api/clima/regioes"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json(
                        "{\"status\":\"INTERNAL_SERVER_ERROR\",\"message\":\"Erro inesperado no clima-service.\"}",
                        true));
        assertThat(saida.getOut() + saida.getErr())
                .contains("ERROR")
                .contains("segredo-interno-xyz");
    }

    @Test
    void metodoNaoSuportado405SemErroNoLog(CapturedOutput saida) throws Exception {
        mvc.perform(delete("/api/clima/regioes"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().json(
                        "{\"status\":\"METHOD_NOT_ALLOWED\",\"message\":\"Método não suportado\"}", true));
        assertThat(saida.getOut() + saida.getErr()).doesNotContain(" ERROR ");
    }

    @Test
    void rotaInexistente404SemErroNoLog(CapturedOutput saida) throws Exception {
        mvc.perform(get("/api/clima/nao-existe"))
                .andExpect(status().isNotFound())
                .andExpect(content().json(
                        "{\"status\":\"NOT_FOUND\",\"message\":\"Recurso não encontrado\"}", true));
        assertThat(saida.getOut() + saida.getErr()).doesNotContain(" ERROR ");
    }

    @RestController
    static class EndpointsDeTeste {
        @GetMapping("/teste/recusa/nao-encontrada")
        void naoEncontrada() {
            throw new RegiaoRecusadaException(MotivoRecusa.NAO_ENCONTRADA);
        }

        @GetMapping("/teste/recusa/inativa")
        void inativa() {
            throw new RegiaoRecusadaException(MotivoRecusa.INATIVA);
        }
    }
}

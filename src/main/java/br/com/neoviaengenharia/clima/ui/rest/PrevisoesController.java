package br.com.neoviaengenharia.clima.ui.rest;

import br.com.neoviaengenharia.clima.app.previsao.ConsultaPrevisao;
import br.com.neoviaengenharia.clima.app.previsao.PrevisaoVigenteService;
import br.com.neoviaengenharia.clima.app.regiao.FonteIndisponivelException;
import br.com.neoviaengenharia.clima.ui.dto.PrevisoesResponse;
import br.com.neoviaengenharia.clima.ui.dto.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clima/previsoes")
@Tag(name = "Previsões", description = "Previsão meteorológica da Nimbus por região")
public class PrevisoesController {

    private final PrevisaoVigenteService service;

    public PrevisoesController(PrevisaoVigenteService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Previsão vigente de até 20 regiões",
            description = "Devolve a rodada mais recente de cada região, na ordem pedida, com erro por região. "
                    + "Só consulta a Nimbus quando a rodada do dia UTC ainda não foi obtida (no máximo a cada 15 min).")
    @ApiResponse(responseCode = "200", description = "Um item por região pedida")
    @ApiResponse(responseCode = "400", description = "Parâmetros inválidos",
            content = @Content(schema = @Schema(implementation = Response.class)))
    @ApiResponse(responseCode = "503", description = "Fonte indisponível para todas as regiões pedidas",
            content = @Content(schema = @Schema(implementation = Response.class)))
    public PrevisoesResponse consultar(
            @Parameter(description = "De 1 a 20 regionId distintos, separados por vírgula", example = "1359,1706")
            @RequestParam(required = false) String regioes,
            @Parameter(description = "Início da janela de pontos (ISO-8601 com fuso), inclusivo",
                    example = "2026-10-15T00:00:00Z")
            @RequestParam(required = false) String inicio,
            @Parameter(description = "Fim da janela de pontos (ISO-8601 com fuso), inclusivo",
                    example = "2026-10-16T00:00:00Z")
            @RequestParam(required = false) String fim) {
        List<Long> ids = ParametrosConsulta.regioes(regioes);
        Instant de = ParametrosConsulta.instante(inicio, "inicio");
        Instant ate = ParametrosConsulta.instante(fim, "fim");
        ParametrosConsulta.ordem(de, ate);
        ConsultaPrevisao consulta = service.consultar(ids, de, ate);
        if (consulta.todasIndisponiveis()) {
            throw new FonteIndisponivelException();
        }
        return PrevisoesResponse.de(consulta);
    }
}

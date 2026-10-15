package br.com.neoviaengenharia.clima.ui.rest;

import br.com.neoviaengenharia.clima.app.previsao.ConsultaPrevisao;
import br.com.neoviaengenharia.clima.app.previsao.PrevisaoHistoricoService;
import br.com.neoviaengenharia.clima.app.previsao.PrevisaoVigenteService;
import br.com.neoviaengenharia.clima.app.regiao.FonteIndisponivelException;
import br.com.neoviaengenharia.clima.ui.dto.PrevisaoRegiaoResponse;
import br.com.neoviaengenharia.clima.ui.dto.PrevisoesResponse;
import br.com.neoviaengenharia.clima.ui.dto.Response;
import br.com.neoviaengenharia.clima.ui.dto.RodadasResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clima/previsoes")
@Tag(name = "Previsões", description = "Previsão meteorológica da Nimbus por região")
public class PrevisoesController {

    private final PrevisaoVigenteService service;
    private final PrevisaoHistoricoService historico;

    public PrevisoesController(PrevisaoVigenteService service, PrevisaoHistoricoService historico) {
        this.service = service;
        this.historico = historico;
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

    @GetMapping("/{regionId}/rodadas")
    @Operation(summary = "Rodadas gravadas de uma região",
            description = "Lista as rodadas com dado no período, da mais recente para a mais antiga. "
                    + "Vale para regiões inativas e removidas e nunca chama a Nimbus.")
    @ApiResponse(responseCode = "200", description = "Rodadas do período")
    @ApiResponse(responseCode = "400", description = "Parâmetros inválidos",
            content = @Content(schema = @Schema(implementation = Response.class)))
    @ApiResponse(responseCode = "404", description = "Região não encontrada",
            content = @Content(schema = @Schema(implementation = Response.class)))
    public RodadasResponse listarRodadas(
            @Parameter(description = "regionId", example = "1359") @PathVariable long regionId,
            @Parameter(description = "Início do período (ISO-8601 com fuso), inclusivo", required = true,
                    example = "2026-10-01T00:00:00Z")
            @RequestParam(required = false) String de,
            @Parameter(description = "Fim do período (ISO-8601 com fuso), inclusivo, no máximo 90 dias depois",
                    required = true, example = "2026-10-31T00:00:00Z")
            @RequestParam(required = false) String ate) {
        Instant de0 = ParametrosConsulta.obrigatorio(de, "de");
        Instant ate0 = ParametrosConsulta.obrigatorio(ate, "ate");
        ParametrosConsulta.periodo(de0, ate0);
        return RodadasResponse.de(ParametrosConsulta.regiaoId(regionId), historico.listar(regionId, de0, ate0));
    }

    @GetMapping("/{regionId}/rodadas/{rodada}")
    @Operation(summary = "Rodada específica de uma região",
            description = "Devolve os pontos gravados da rodada, com as variáveis já traduzidas. "
                    + "Vale para regiões inativas e removidas e nunca chama a Nimbus.")
    @ApiResponse(responseCode = "200", description = "Pontos da rodada")
    @ApiResponse(responseCode = "400", description = "Parâmetros inválidos",
            content = @Content(schema = @Schema(implementation = Response.class)))
    @ApiResponse(responseCode = "404", description = "Região ou rodada não encontrada",
            content = @Content(schema = @Schema(implementation = Response.class)))
    public PrevisaoRegiaoResponse rodada(
            @Parameter(description = "regionId", example = "1359") @PathVariable long regionId,
            @Parameter(description = "Instante da rodada (ISO-8601 com fuso)", required = true,
                    example = "2026-10-14T00:00:00Z")
            @PathVariable String rodada,
            @Parameter(description = "Início da janela de pontos (ISO-8601 com fuso), inclusivo",
                    example = "2026-10-14T00:00:00Z")
            @RequestParam(required = false) String inicio,
            @Parameter(description = "Fim da janela de pontos (ISO-8601 com fuso), inclusivo",
                    example = "2026-10-15T00:00:00Z")
            @RequestParam(required = false) String fim) {
        Instant instanteRodada = ParametrosConsulta.instante(rodada, "rodada");
        Instant de = ParametrosConsulta.instante(inicio, "inicio");
        Instant ate = ParametrosConsulta.instante(fim, "fim");
        ParametrosConsulta.ordem(de, ate);
        return PrevisaoRegiaoResponse.de(
                historico.rodada(ParametrosConsulta.regiaoId(regionId), instanteRodada, de, ate));
    }
}

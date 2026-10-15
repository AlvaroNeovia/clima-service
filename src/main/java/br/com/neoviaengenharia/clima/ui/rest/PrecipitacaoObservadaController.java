package br.com.neoviaengenharia.clima.ui.rest;

import br.com.neoviaengenharia.clima.app.observado.PrecipitacaoObservadaService;
import br.com.neoviaengenharia.clima.domain.observado.JanelaObservada;
import br.com.neoviaengenharia.clima.ui.dto.PrecipitacaoObservadaResponse;
import br.com.neoviaengenharia.clima.ui.dto.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clima/precipitacoes-observadas")
@Tag(name = "Precipitação observada", description = "Série horária de precipitação observada por região")
public class PrecipitacaoObservadaController {

    private final PrecipitacaoObservadaService service;

    public PrecipitacaoObservadaController(PrecipitacaoObservadaService service) {
        this.service = service;
    }

    @GetMapping("/{regionId}")
    @Operation(summary = "Série horária de precipitação observada de uma região",
            description = "Janela [inicio, fim) de até 7 dias, em hora cheia. Considera só as horas até a hora "
                    + "corrente; horas sem dado são omitidas. Horas definitivas vêm dos caches e do banco; a Nimbus "
                    + "só é consultada para a hora corrente, as 6 h recentes e as horas nunca tentadas, no máximo "
                    + "1x/hora. Regiões inativas ou removidas devolvem só o histórico gravado.")
    @ApiResponse(responseCode = "200", description = "Série observada; desatualizado=true se a Nimbus falhou e "
            + "veio o histórico gravado")
    @ApiResponse(responseCode = "400", description = "Parâmetros inválidos ou janela acima de 7 dias",
            content = @Content(schema = @Schema(implementation = Response.class)))
    @ApiResponse(responseCode = "404", description = "Região não encontrada",
            content = @Content(schema = @Schema(implementation = Response.class)))
    @ApiResponse(responseCode = "503", description = "Fonte de dados climáticos indisponível e nada gravado na janela",
            content = @Content(schema = @Schema(implementation = Response.class)))
    public PrecipitacaoObservadaResponse consultar(
            @PathVariable long regionId,
            @Parameter(description = "Início (inclusivo), ISO-8601 com fuso, hora cheia", example = "2026-10-14T00:00:00Z")
            @RequestParam(required = false) String inicio,
            @Parameter(description = "Fim (exclusivo), ISO-8601 com fuso, hora cheia", example = "2026-10-15T00:00:00Z")
            @RequestParam(required = false) String fim) {
        JanelaObservada janela = JanelaObservada.de(inicio, fim);
        return PrecipitacaoObservadaResponse.de(service.consultar(regionId, janela));
    }
}

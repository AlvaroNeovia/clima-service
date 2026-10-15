package br.com.neoviaengenharia.clima.ui.rest;

import br.com.neoviaengenharia.clima.app.regiao.CatalogoRegioesService;
import br.com.neoviaengenharia.clima.ui.dto.CatalogoRegioesResponse;
import br.com.neoviaengenharia.clima.ui.dto.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clima/regioes")
@Tag(name = "Regiões", description = "Catálogo de regiões de previsão da Nimbus")
public class RegioesController {

    private final CatalogoRegioesService service;

    public RegioesController(CatalogoRegioesService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Lista o catálogo de regiões", description = "Lê dos caches e do banco; só consulta a Nimbus se o catálogo tiver vencido.")
    @ApiResponse(responseCode = "200", description = "Catálogo de regiões")
    @ApiResponse(responseCode = "503", description = "Fonte de dados climáticos indisponível e sem catálogo gravado",
            content = @Content(schema = @Schema(implementation = Response.class)))
    public CatalogoRegioesResponse listar() {
        return CatalogoRegioesResponse.de(service.obter());
    }

    @PostMapping("/atualizar")
    @Operation(summary = "Atualiza o catálogo de regiões", description = "Endpoint interno: ignora os caches e busca na Nimbus se o catálogo gravado tiver vencido.")
    @ApiResponse(responseCode = "200", description = "Catálogo de regiões")
    @ApiResponse(responseCode = "503", description = "Fonte de dados climáticos indisponível e sem catálogo gravado",
            content = @Content(schema = @Schema(implementation = Response.class)))
    public CatalogoRegioesResponse atualizar() {
        return CatalogoRegioesResponse.de(service.atualizar());
    }
}

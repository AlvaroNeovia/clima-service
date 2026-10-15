package br.com.neoviaengenharia.clima.ui.exceptions;

import br.com.neoviaengenharia.clima.app.regiao.FonteIndisponivelException;
import br.com.neoviaengenharia.clima.ui.dto.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ExceptionManager {

    private static final Logger log = LoggerFactory.getLogger(ExceptionManager.class);

    @ExceptionHandler(RegiaoRecusadaException.class)
    public ResponseEntity<Response> regiaoRecusada(RegiaoRecusadaException e) {
        return switch (e.motivo()) {
            case NAO_ENCONTRADA -> resposta(HttpStatus.NOT_FOUND, "Região não encontrada");
            case INATIVA -> resposta(HttpStatus.UNPROCESSABLE_ENTITY, "Região inativa");
        };
    }

    @ExceptionHandler(FonteIndisponivelException.class)
    public ResponseEntity<Response> fonteIndisponivel(FonteIndisponivelException e) {
        return resposta(HttpStatus.SERVICE_UNAVAILABLE, "Fonte de dados climáticos indisponível");
    }

    /** Erros do próprio Spring MVC (405, 404, 406, 400...): mantém o status, com mensagem fixa e sem detalhes. */
    private ResponseEntity<Response> erroDoFramework(ErrorResponse e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        log.debug("Requisição recusada pelo framework: {} ({})", status, e.getClass().getSimpleName());
        return ResponseEntity.status(status)
                .headers(e.getHeaders())
                .body(new Response(status, mensagemPadrao(status)));
    }

    private static String mensagemPadrao(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "Requisição inválida";
            case NOT_FOUND -> "Recurso não encontrado";
            case METHOD_NOT_ALLOWED -> "Método não suportado";
            case NOT_ACCEPTABLE -> "Formato de resposta não aceito";
            case UNSUPPORTED_MEDIA_TYPE -> "Tipo de conteúdo não suportado";
            default -> status.is5xxServerError() ? "Erro inesperado no clima-service." : "Requisição não atendida";
        };
    }

    /** Última rede (e erros do framework, ver acima): loga o erro e não devolve detalhes ao consumidor. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response> inesperado(Exception e) {
        if (e instanceof ErrorResponse erroDoFramework) { // ErrorResponse é interface: não serve em @ExceptionHandler
            return erroDoFramework(erroDoFramework);
        }
        log.error("Erro inesperado no clima-service", e);
        return resposta(HttpStatus.INTERNAL_SERVER_ERROR, "Erro inesperado no clima-service.");
    }

    private static ResponseEntity<Response> resposta(HttpStatus status, String mensagem) {
        return ResponseEntity.status(status).body(new Response(status, mensagem));
    }
}

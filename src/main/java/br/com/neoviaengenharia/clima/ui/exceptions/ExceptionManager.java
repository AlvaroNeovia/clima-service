package br.com.neoviaengenharia.clima.ui.exceptions;

import br.com.neoviaengenharia.clima.app.regiao.FonteIndisponivelException;
import br.com.neoviaengenharia.clima.ui.dto.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /** Última rede: loga o erro e não devolve detalhes ao consumidor. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response> inesperado(Exception e) {
        log.error("Erro inesperado no clima-service", e);
        return resposta(HttpStatus.INTERNAL_SERVER_ERROR, "Erro inesperado no clima-service.");
    }

    private static ResponseEntity<Response> resposta(HttpStatus status, String mensagem) {
        return ResponseEntity.status(status).body(new Response(status, mensagem));
    }
}

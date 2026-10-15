package br.com.neoviaengenharia.clima.ui.exceptions;

import br.com.neoviaengenharia.clima.ui.dto.Response;
import br.com.neoviaengenharia.clima.ui.rest.PrevisoesController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Erros próprios dos endpoints de previsão. O resto (503 da fonte, 500) cai no {@link ExceptionManager}; o handler
 * fica separado para não disputar o {@code ExceptionManager} com os outros slices.
 */
@RestControllerAdvice(assignableTypes = PrevisoesController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PrevisaoExceptionHandler {

    @ExceptionHandler(ParametrosInvalidosException.class)
    public ResponseEntity<Response> parametrosInvalidos(ParametrosInvalidosException e) {
        return ResponseEntity.badRequest().body(new Response(HttpStatus.BAD_REQUEST, e.getMessage()));
    }
}

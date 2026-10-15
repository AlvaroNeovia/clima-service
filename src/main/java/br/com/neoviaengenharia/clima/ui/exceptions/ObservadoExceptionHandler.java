package br.com.neoviaengenharia.clima.ui.exceptions;

import br.com.neoviaengenharia.clima.app.observado.RegiaoNaoEncontradaException;
import br.com.neoviaengenharia.clima.domain.observado.JanelaExcedidaException;
import br.com.neoviaengenharia.clima.domain.observado.ParametrosInvalidosException;
import br.com.neoviaengenharia.clima.ui.dto.Response;
import br.com.neoviaengenharia.clima.ui.rest.PrecipitacaoObservadaController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Erros próprios do observado (S4 §9). Vem antes do {@link ExceptionManager}, que segue tratando o 503 e o 500.
 */
@RestControllerAdvice(assignableTypes = PrecipitacaoObservadaController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ObservadoExceptionHandler {

    @ExceptionHandler({ParametrosInvalidosException.class, JanelaExcedidaException.class})
    public ResponseEntity<Response> parametros(RuntimeException e) {
        return resposta(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response> tipoInvalido(MethodArgumentTypeMismatchException e) {
        return resposta(HttpStatus.BAD_REQUEST, "Parâmetros inválidos: " + e.getName() + " deve ser numérico");
    }

    @ExceptionHandler(RegiaoNaoEncontradaException.class)
    public ResponseEntity<Response> regiaoNaoEncontrada(RegiaoNaoEncontradaException e) {
        return resposta(HttpStatus.NOT_FOUND, "Região não encontrada");
    }

    private static ResponseEntity<Response> resposta(HttpStatus status, String mensagem) {
        return ResponseEntity.status(status).body(new Response(status, mensagem));
    }
}

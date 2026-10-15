package br.com.neoviaengenharia.clima.ui.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

/** Formato de erro: {@code {"status": "<HttpStatus>", "message": "<texto>"}} (igual ao pgo-api-service). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Response(HttpStatus status, String message) {}

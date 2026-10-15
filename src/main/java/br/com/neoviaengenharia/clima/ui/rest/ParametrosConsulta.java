package br.com.neoviaengenharia.clima.ui.rest;

import br.com.neoviaengenharia.clima.ui.exceptions.ParametrosInvalidosException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Conversão e validação dos parâmetros de consulta de previsão. */
final class ParametrosConsulta {

    static final int MAXIMO_REGIOES = 20;

    private ParametrosConsulta() {}

    /** Lista "1359,1706" → ids distintos, na ordem, de 1 a {@value #MAXIMO_REGIOES}. */
    static List<Long> regioes(String valor) {
        if (valor == null || valor.isBlank()) {
            throw new ParametrosInvalidosException("regioes é obrigatório");
        }
        List<Long> ids = new ArrayList<>();
        Set<Long> vistos = new LinkedHashSet<>();
        for (String parte : valor.split(",", -1)) {
            String texto = parte.strip();
            long id;
            try {
                id = Long.parseLong(texto);
            } catch (NumberFormatException e) {
                throw new ParametrosInvalidosException("regioes deve conter inteiros positivos separados por vírgula");
            }
            if (id <= 0) {
                throw new ParametrosInvalidosException("regioes deve conter inteiros positivos separados por vírgula");
            }
            if (!vistos.add(id)) {
                throw new ParametrosInvalidosException("regioes com id repetido: " + id);
            }
            ids.add(id);
        }
        if (ids.size() > MAXIMO_REGIOES) {
            throw new ParametrosInvalidosException("regioes aceita no máximo " + MAXIMO_REGIOES + " ids");
        }
        return ids;
    }

    /** ISO-8601 com fuso ({@code Z} ou {@code ±hh:mm}); {@code null}/ausente → {@code null}. */
    static Instant instante(String valor, String nome) {
        if (valor == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(valor).toInstant();
        } catch (DateTimeParseException e) {
            throw new ParametrosInvalidosException(nome + " deve estar em ISO-8601 com fuso (ex.: 2026-10-15T00:00:00Z)");
        }
    }

    static void ordem(Instant inicio, Instant fim) {
        if (inicio != null && fim != null && fim.isBefore(inicio)) {
            throw new ParametrosInvalidosException("fim deve ser maior ou igual a inicio");
        }
    }
}

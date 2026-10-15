package br.com.neoviaengenharia.clima.ui.rest;

import br.com.neoviaengenharia.clima.ui.exceptions.ParametrosInvalidosException;
import java.time.Duration;
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
    static final Duration PERIODO_MAXIMO = Duration.ofDays(90);

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

    /** {@code regionId} do caminho: inteiro positivo. */
    static long regiaoId(long valor) {
        if (valor <= 0) {
            throw new ParametrosInvalidosException("regionId deve ser positivo");
        }
        return valor;
    }

    /** Parâmetro obrigatório que é um instante ISO-8601 com fuso. */
    static Instant obrigatorio(String valor, String nome) {
        if (valor == null || valor.isBlank()) {
            throw new ParametrosInvalidosException(nome + " é obrigatório");
        }
        return instante(valor, nome);
    }

    /** {@code ate} não pode vir antes de {@code de}, nem a mais de {@value #PERIODO_MAXIMO} dias depois. */
    static void periodo(Instant de, Instant ate) {
        if (ate.isBefore(de)) {
            throw new ParametrosInvalidosException("ate deve ser maior ou igual a de");
        }
        if (Duration.between(de, ate).compareTo(PERIODO_MAXIMO) > 0) {
            throw new ParametrosInvalidosException("o período máximo é de 90 dias");
        }
    }
}

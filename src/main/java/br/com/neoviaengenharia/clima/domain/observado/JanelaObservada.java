package br.com.neoviaengenharia.clima.domain.observado;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Janela {@code [inicio, fim)} validada: ISO-8601 com fuso, hora cheia em UTC, até 7 dias (S4-FR-02). */
public record JanelaObservada(Instant inicio, Instant fim) {

    static final Duration MAXIMA = Duration.ofDays(7);

    public static JanelaObservada de(String inicio, String fim) {
        Instant i = instante("inicio", inicio);
        Instant f = instante("fim", fim);
        if (!f.isAfter(i)) {
            throw new ParametrosInvalidosException("fim deve ser posterior a inicio");
        }
        if (Duration.between(i, f).compareTo(MAXIMA) > 0) {
            throw new JanelaExcedidaException();
        }
        return new JanelaObservada(i, f);
    }

    private static Instant instante(String nome, String valor) {
        if (valor == null || valor.isBlank()) {
            throw new ParametrosInvalidosException(nome + " é obrigatório");
        }
        Instant instante;
        try {
            instante = OffsetDateTime.parse(valor.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new ParametrosInvalidosException(nome + " deve estar em ISO-8601 com fuso");
        }
        if (instante.getEpochSecond() % 3600 != 0 || instante.getNano() != 0) {
            throw new ParametrosInvalidosException(nome + " deve estar em hora cheia");
        }
        return instante;
    }

    /** Horas da janela até a hora corrente, inclusive (S4-FR-03), em ordem crescente. */
    public List<Instant> horasAte(Instant agora) {
        Instant limite = agora.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1));
        Instant ate = fim.isBefore(limite) ? fim : limite;
        List<Instant> horas = new ArrayList<>();
        for (Instant h = inicio; h.isBefore(ate); h = h.plus(Duration.ofHours(1))) {
            horas.add(h);
        }
        return horas;
    }
}

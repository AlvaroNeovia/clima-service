package br.com.neoviaengenharia.clima.domain.observado;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * O que se sabe de uma hora H de uma região: a leitura gravada (se houver), a marca {@code semDado} e a
 * última tentativa bem-sucedida na Nimbus que cobriu a hora (S4-FR-05..08).
 */
public record EstadoHora(
        Instant dataHora, LeituraHora leitura, Instant obtidoEm, boolean semDado, Instant ultimaTentativaEm) {

    static final Duration INTERVALO_ENTRE_BUSCAS = Duration.ofHours(1);
    static final Duration RECONCILIACAO = Duration.ofHours(6);

    public static EstadoHora vazio(Instant dataHora) {
        return new EstadoHora(dataHora, null, null, false, null);
    }

    public boolean temDado() {
        return leitura != null;
    }

    /** Fechada há mais de 6 h: {@code H+1h < agora-6h}. */
    public static boolean antiga(Instant dataHora, Instant agora) {
        return dataHora.plus(Duration.ofHours(1)).isBefore(agora.minus(RECONCILIACAO));
    }

    public boolean definitiva(Instant agora) {
        return semDado || (temDado() && antiga(dataHora, agora));
    }

    /** Não definitiva e sem tentativa há 1 h ou menos. */
    public boolean deveBuscar(Instant agora) {
        return !definitiva(agora)
                && (ultimaTentativaEm == null || agora.isAfter(ultimaTentativaEm.plus(INTERVALO_ENTRE_BUSCAS)));
    }

    /**
     * Até quando o estado pode ser servido do cache: vazio para definitiva (sem prazo); {@code ultimaTentativaEm
     * + 1h} quando houve tentativa; {@link Instant#MIN} (não cacheável) quando nunca foi tentada.
     */
    public Optional<Instant> validoAte(Instant agora) {
        if (definitiva(agora)) {
            return Optional.empty();
        }
        return Optional.of(ultimaTentativaEm == null ? Instant.MIN : ultimaTentativaEm.plus(INTERVALO_ENTRE_BUSCAS));
    }
}

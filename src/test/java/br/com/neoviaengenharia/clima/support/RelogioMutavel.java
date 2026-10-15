package br.com.neoviaengenharia.clima.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Clock de teste em UTC cujo instante é controlado pelo teste. */
public class RelogioMutavel extends Clock {

    public static final Instant INSTANTE_PADRAO = Instant.parse("2026-10-15T12:00:00Z");

    private final AtomicReference<Instant> agora = new AtomicReference<>(INSTANTE_PADRAO);

    public void definir(Instant instante) {
        agora.set(instante);
    }

    public void avancar(Duration duracao) {
        agora.updateAndGet(i -> i.plus(duracao));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("RelogioMutavel só opera em UTC");
    }

    @Override
    public Instant instant() {
        return agora.get();
    }
}

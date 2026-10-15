package br.com.neoviaengenharia.clima.domain.regiao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogoSnapshotTest {

    private final CatalogoSnapshot snapshot = new CatalogoSnapshot(List.of(), Instant.parse("2026-10-15T12:00:00Z"));

    @Test
    void vigenteAntesDe24h() {
        assertThat(snapshot.vigente(Instant.parse("2026-10-16T11:59:59Z"))).isTrue();
    }

    @Test
    void venceExatamenteEm24h() {
        assertThat(snapshot.validoAte()).isEqualTo(Instant.parse("2026-10-16T12:00:00Z"));
        assertThat(snapshot.vigente(Instant.parse("2026-10-16T12:00:00Z"))).isFalse();
    }
}

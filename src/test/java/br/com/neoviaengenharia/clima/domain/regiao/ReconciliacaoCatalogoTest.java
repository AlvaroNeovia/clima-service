package br.com.neoviaengenharia.clima.domain.regiao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconciliacaoCatalogoTest {

    private static final Instant ANTES = Instant.parse("2026-10-14T12:00:00Z");
    private static final Instant AGORA = Instant.parse("2026-10-15T12:00:00Z");

    private static Regiao gravada(long id, String nome, boolean removida) {
        Regiao r = Regiao.nova(new RegiaoNimbus(id, nome, "alias-" + id, false, 10L), ANTES);
        if (removida) {
            r.marcarRemovida(ANTES);
        }
        return r;
    }

    @Test
    void insereRegiaoNova() {
        List<Regiao> salvar = ReconciliacaoCatalogo.aplicar(
                List.of(), List.of(new RegiaoNimbus(1359, "Sul", "sul", false, 5L)), AGORA);

        assertThat(salvar).hasSize(1);
        Regiao r = salvar.get(0);
        assertThat(r.getRegionId()).isEqualTo(1359);
        assertThat(r.getNome()).isEqualTo("Sul");
        assertThat(r.getAlias()).isEqualTo("sul");
        assertThat(r.isInativa()).isFalse();
        assertThat(r.getEstadoId()).isEqualTo(5L);
        assertThat(r.isRemovida()).isFalse();
        assertThat(r.getAtualizadoEm()).isEqualTo(AGORA);
    }

    @Test
    void atualizaNomeAliasInativaEstado() {
        Regiao existente = gravada(1359, "Antigo", false);

        List<Regiao> salvar = ReconciliacaoCatalogo.aplicar(
                List.of(existente), List.of(new RegiaoNimbus(1359, "Novo", "novo", true, 99L)), AGORA);

        assertThat(salvar).containsExactly(existente);
        assertThat(existente.getNome()).isEqualTo("Novo");
        assertThat(existente.getAlias()).isEqualTo("novo");
        assertThat(existente.isInativa()).isTrue();
        assertThat(existente.getEstadoId()).isEqualTo(99L);
        assertThat(existente.getAtualizadoEm()).isEqualTo(AGORA);
    }

    @Test
    void marcaRemovidaQuemNaoVeio() {
        Regiao r1359 = gravada(1359, "A", false);
        Regiao r1706 = gravada(1706, "B", false);

        List<Regiao> salvar = ReconciliacaoCatalogo.aplicar(
                List.of(r1359, r1706), List.of(new RegiaoNimbus(1359, "A", "alias-1359", false, 10L)), AGORA);

        assertThat(salvar).containsExactlyInAnyOrder(r1359, r1706);
        assertThat(r1706.isRemovida()).isTrue();
        assertThat(r1706.getAtualizadoEm()).isEqualTo(AGORA);
        assertThat(r1359.isRemovida()).isFalse();
    }

    @Test
    void reativaRemovidaQueVoltou() {
        Regiao removida = gravada(1706, "B", true);

        ReconciliacaoCatalogo.aplicar(
                List.of(removida), List.of(new RegiaoNimbus(1706, "B", "alias-1706", false, 10L)), AGORA);

        assertThat(removida.isRemovida()).isFalse();
    }
}

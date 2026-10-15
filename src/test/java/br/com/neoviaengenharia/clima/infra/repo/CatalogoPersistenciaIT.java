package br.com.neoviaengenharia.clima.infra.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CatalogoPersistenciaIT {

    @Autowired
    BuscaCatalogoRepository buscas;

    @Autowired
    ControleCatalogoRepository controle;

    @Autowired
    RegiaoRepository regioes;

    @Test
    void migracaoValidaContraEntidades() {
        assertThat(regioes.findAllByRemovidaFalseOrderByRegionId()).isEmpty();
    }

    @Test
    void ultimaBuscaEhAMaisRecente() {
        buscas.save(new BuscaCatalogo(Instant.parse("2026-10-14T12:00:00Z"), 10));
        buscas.save(new BuscaCatalogo(Instant.parse("2026-10-15T12:00:00Z"), 20));
        buscas.save(new BuscaCatalogo(Instant.parse("2026-10-13T12:00:00Z"), 5));

        BuscaCatalogo ultima = buscas.findTopByOrderByObtidoEmDesc().orElseThrow();

        assertThat(ultima.getObtidoEm()).isEqualTo(Instant.parse("2026-10-15T12:00:00Z"));
        assertThat(ultima.getTotalRegioes()).isEqualTo(20);
    }

    @Test
    void controleTemLinhaUnica() {
        ControleCatalogo c = controle.travar();

        assertThat(c.getId()).isEqualTo(1);
        assertThat(c.getUltimaFalhaEm()).isNull();
        assertThat(controle.count()).isEqualTo(1);
    }

    @Test
    void ultimaFalhaEmLeSoOValor() {
        assertThat(controle.ultimaFalhaEm()).isEmpty();

        controle.travar().registrarFalha(Instant.parse("2026-10-15T12:00:00Z"));

        assertThat(controle.ultimaFalhaEm()).contains(Instant.parse("2026-10-15T12:00:00Z"));
    }
}

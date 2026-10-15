package br.com.neoviaengenharia.clima.infra.repo;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.neoviaengenharia.clima.domain.regiao.Regiao;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoNimbus;
import br.com.neoviaengenharia.clima.support.IntegracaoBase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TentativaPrevisaoRepositoryIT extends IntegracaoBase {

    @Autowired
    TentativaPrevisaoRepository tentativas;

    @Autowired
    RegiaoRepository regioes;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transacoes;

    @BeforeEach
    void limpar() {
        PrevisaoTabelas.limparComRegioes(jdbc);
        Instant agora = Instant.parse("2026-10-15T12:00:00Z");
        regioes.save(Regiao.nova(new RegiaoNimbus(1359, "Franca", "f", false, 25), agora));
        regioes.save(Regiao.nova(new RegiaoNimbus(1706, "Votorantim", "v", false, 25), agora));
    }

    @AfterEach
    void limparDepois() {
        PrevisaoTabelas.limparComRegioes(jdbc);
    }

    @Test
    void garantirEhIdempotente() {
        tentativas.garantir(1359);
        tentativas.garantir(1359);

        assertThat(tentativas.count()).isEqualTo(1);
        TentativaPrevisao t = tentativas.findById(1359L).orElseThrow();
        assertThat(t.getUltimaTentativaEm()).isNull();
        assertThat(t.isUltimaFalhou()).isFalse();
    }

    @Test
    void travarDevolveEmOrdem() {
        tentativas.garantir(1706);
        tentativas.garantir(1359);
        Instant agora = Instant.parse("2026-10-15T12:00:00Z");
        TransactionTemplate tx = new TransactionTemplate(transacoes);

        List<Long> ids = tx.execute(s -> {
            List<TentativaPrevisao> travadas = tentativas.travar(List.of(1706L, 1359L));
            travadas.forEach(t -> t.registrar(agora, true));
            return travadas.stream().map(TentativaPrevisao::getRegionId).toList();
        });

        assertThat(ids).containsExactly(1359L, 1706L);
        TentativaPrevisao t = tentativas.findById(1706L).orElseThrow();
        assertThat(t.getUltimaTentativaEm()).isEqualTo(agora);
        assertThat(t.isUltimaFalhou()).isTrue();
    }
}

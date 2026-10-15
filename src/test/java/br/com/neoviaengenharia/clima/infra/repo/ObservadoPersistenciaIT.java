package br.com.neoviaengenharia.clima.infra.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.neoviaengenharia.clima.domain.observado.EstadoHora;
import br.com.neoviaengenharia.clima.domain.observado.Estacao;
import br.com.neoviaengenharia.clima.domain.observado.LeituraHora;
import br.com.neoviaengenharia.clima.domain.regiao.Regiao;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoNimbus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ObservadoPersistenciaIT {

    static final Instant H0 = Instant.parse("2026-10-14T00:00:00Z");
    static final Instant H1 = Instant.parse("2026-10-14T01:00:00Z");
    static final Instant H2 = Instant.parse("2026-10-14T02:00:00Z");
    static final Instant OBTIDO = Instant.parse("2026-10-14T07:05:12Z");

    @Autowired
    HoraObservadaRepository horas;

    @Autowired
    TentativaObservadoRepository tentativas;

    @Autowired
    TravaRegiaoRepository trava;

    @Autowired
    RegiaoRepository regioes;

    @Autowired
    TestEntityManager em;

    @BeforeEach
    void regiao() {
        regioes.save(Regiao.nova(new RegiaoNimbus(1674, "Região 1674", "r1674", false, 25), OBTIDO));
    }

    private static LeituraHora leitura(String soma) {
        return new LeituraHora(new Estacao(3078, "Prudentópolis", "mg_h-823"), new BigDecimal(soma),
                new BigDecimal(soma), H0, null, null, false);
    }

    private HoraObservada relida(Instant h) {
        em.flush();
        em.clear();
        return horas.findAllByRegionIdAndDataHoraGreaterThanEqualAndDataHoraLessThan(1674, h, h.plusSeconds(3600))
                .get(0);
    }

    @Test
    void migracaoValidaContraEntidades() {
        assertThat(horas.count()).isZero();
        assertThat(tentativas.count()).isZero();
    }

    @Test
    void gravaELeHoraComDadoENulos() {
        horas.save(HoraObservada.de(1674, new EstadoHora(H0, leitura("0"), OBTIDO, false, OBTIDO)));

        HoraObservada h = relida(H0);

        assertThat(h.isSemDado()).isFalse();
        assertThat(h.leitura()).isEqualTo(leitura("0"));
        assertThat(h.leitura().primeiraLeitura()).isNull();
        assertThat(h.leitura().interpolado()).isFalse();
        assertThat(h.estado(OBTIDO)).isEqualTo(new EstadoHora(H0, leitura("0"), OBTIDO, false, OBTIDO));
    }

    @Test
    void gravaSemDado() {
        horas.save(HoraObservada.de(1674, new EstadoHora(H0, null, null, true, OBTIDO)));

        HoraObservada h = relida(H0);

        assertThat(h.isSemDado()).isTrue();
        assertThat(h.leitura()).isNull();
        assertThat(h.getStationId()).isNull();
        assertThat(h.estado(OBTIDO)).isEqualTo(new EstadoHora(H0, null, null, true, OBTIDO));
    }

    @Test
    void atualizaComNovoEstado() {
        HoraObservada h = horas.save(HoraObservada.de(1674, new EstadoHora(H0, leitura("1"), OBTIDO, false, OBTIDO)));
        Instant depois = OBTIDO.plusSeconds(3600);
        h.atualizarCom(new EstadoHora(H0, leitura("2.5"), depois, false, depois));

        HoraObservada relida = relida(H0);

        assertThat(relida.leitura().precipitacaoAcumulada()).isEqualTo(new BigDecimal("2.5"));
        assertThat(relida.getObtidoEm()).isEqualTo(depois);
    }

    @Test
    void unicoPorRegiaoEHora() {
        horas.saveAndFlush(HoraObservada.de(1674, new EstadoHora(H0, leitura("0"), OBTIDO, false, OBTIDO)));

        assertThatThrownBy(() -> horas.saveAndFlush(
                HoraObservada.de(1674, new EstadoHora(H0, leitura("1"), OBTIDO, false, OBTIDO))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void buscaPorJanelaEhFimExclusivo() {
        for (Instant h : new Instant[] {H0, H1, H2}) {
            horas.save(HoraObservada.de(1674, new EstadoHora(h, leitura("0"), OBTIDO, false, OBTIDO)));
            tentativas.save(new TentativaObservado(1674, h, OBTIDO));
        }
        em.flush();

        assertThat(horas.findAllByRegionIdAndDataHoraGreaterThanEqualAndDataHoraLessThan(1674, H0, H2))
                .extracting(HoraObservada::getDataHora).containsExactlyInAnyOrder(H0, H1);
        assertThat(tentativas.findAllByRegionIdAndDataHoraGreaterThanEqualAndDataHoraLessThan(1674, H1, H2.plusSeconds(3600)))
                .extracting(TentativaObservado::getDataHora).containsExactlyInAnyOrder(H1, H2);
    }

    @Test
    void tentativaEhAtualizada() {
        TentativaObservado t = tentativas.save(new TentativaObservado(1674, H0, OBTIDO));
        Instant depois = OBTIDO.plusSeconds(4000);
        t.registrar(depois);
        em.flush();
        em.clear();

        assertThat(tentativas.findAllByRegionIdAndDataHoraGreaterThanEqualAndDataHoraLessThan(1674, H0, H1))
                .singleElement().satisfies(x -> assertThat(x.getUltimaTentativaEm()).isEqualTo(depois));
    }

    @Test
    void decimalVoltaNormalizado() {
        horas.save(HoraObservada.de(1674, new EstadoHora(H0, leitura("0"), OBTIDO, false, OBTIDO)));
        horas.save(HoraObservada.de(1674, new EstadoHora(H1, leitura("1.25"), OBTIDO, false, OBTIDO)));

        assertThat(relida(H0).leitura().precipitacaoAcumulada().toString()).isEqualTo("0");
        assertThat(relida(H1).leitura().precipitacaoAcumulada().toString()).isEqualTo("1.25");
    }

    @Test
    void travarDevolveRegiaoDentroDeTransacao() {
        assertThat(trava.travar(1674)).hasValueSatisfying(r -> assertThat(r.getRegionId()).isEqualTo(1674));
        assertThat(trava.travar(99999)).isEmpty();
    }
}

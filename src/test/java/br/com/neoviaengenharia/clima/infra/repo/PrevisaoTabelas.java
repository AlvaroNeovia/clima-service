package br.com.neoviaengenharia.clima.infra.repo;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Limpeza das tabelas do S2 nos testes (filhos antes dos pais, por causa das FKs). Os testes do S2 limpam também
 * no {@code @AfterEach}: os do S1 apagam {@code regiao} e falhariam pela FK se sobrassem linhas aqui.
 */
public final class PrevisaoTabelas {

    private PrevisaoTabelas() {}

    public static void limpar(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM clima.ponto_previsao");
        jdbc.update("DELETE FROM clima.rodada_previsao");
        jdbc.update("DELETE FROM clima.tentativa_previsao");
    }

    /** Como {@link #limpar}, e apaga também as regiões. */
    public static void limparComRegioes(JdbcTemplate jdbc) {
        limpar(jdbc);
        jdbc.update("DELETE FROM clima.regiao");
    }
}

package br.com.neoviaengenharia.clima.app.regiao;

/** A Nimbus falhou e não há catálogo gravado para servir (503). */
public class FonteIndisponivelException extends RuntimeException {

    public FonteIndisponivelException() {
        super("Fonte de dados climáticos indisponível");
    }
}

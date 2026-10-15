package br.com.neoviaengenharia.clima.app.previsao;

/** Erro de uma região dentro de uma resposta de previsão. */
public enum CodigoErroRegiao {
    NAO_ENCONTRADA("Região não encontrada"),
    INATIVA("Região inativa"),
    FONTE_INDISPONIVEL("Fonte de dados climáticos indisponível");

    private final String mensagem;

    CodigoErroRegiao(String mensagem) {
        this.mensagem = mensagem;
    }

    public String mensagem() {
        return mensagem;
    }
}

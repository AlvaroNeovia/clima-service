package br.com.neoviaengenharia.clima.domain.regiao;

/** Item do catálogo já validado, vindo da Nimbus. */
public record RegiaoNimbus(long regionId, String nome, String alias, boolean inativa, long estadoId) {}

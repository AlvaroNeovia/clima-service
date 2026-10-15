package br.com.neoviaengenharia.clima.app.regiao;

import br.com.neoviaengenharia.clima.domain.regiao.CatalogoSnapshot;

/** Catálogo devolvido ao consumidor; {@code desatualizado} indica que a Nimbus falhou e veio o último gravado. */
public record CatalogoResultado(CatalogoSnapshot snapshot, boolean desatualizado) {}

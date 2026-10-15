package br.com.neoviaengenharia.clima.app.previsao;

import br.com.neoviaengenharia.clima.domain.previsao.FiltroJanela;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaResumo;
import br.com.neoviaengenharia.clima.infra.cache.PrevisaoCache;
import br.com.neoviaengenharia.clima.infra.repo.RegiaoRepository;
import br.com.neoviaengenharia.clima.infra.repo.RodadaPrevisaoRepositorio;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Histórico de rodadas (S3): listagem e rodada específica. Só lê SQL e cache — nunca chama a Nimbus. Região inativa
 * ou removida vale, desde que tenha histórico (S3-FR-07).
 */
@Service
public class PrevisaoHistoricoService {

    private final RodadaPrevisaoRepositorio rodadas;
    private final PrevisaoCache cache;
    private final RegiaoRepository regioes;

    public PrevisaoHistoricoService(RodadaPrevisaoRepositorio rodadas, PrevisaoCache cache, RegiaoRepository regioes) {
        this.rodadas = rodadas;
        this.cache = cache;
        this.regioes = regioes;
    }

    @Transactional(readOnly = true)
    public List<RodadaResumo> listar(long regionId, Instant de, Instant ate) {
        exigirRegiao(regionId);
        return rodadas.listar(regionId, de, ate);
    }

    public ItemPrevisao rodada(long regionId, Instant rodada, Instant inicio, Instant fim) {
        return cache.obterRodada(regionId, rodada)
                .map(r -> ItemPrevisao.com(r.comPontos(FiltroJanela.aplicar(r.pontos(), inicio, fim)), false))
                .orElseGet(() -> rodadaDoSql(regionId, rodada, inicio, fim));
    }

    private ItemPrevisao rodadaDoSql(long regionId, Instant rodada, Instant inicio, Instant fim) {
        exigirRegiao(regionId);
        return rodadas.buscar(regionId, rodada)
                .map(r -> {
                    cache.guardarRodada(r);
                    return ItemPrevisao.com(r.comPontos(FiltroJanela.aplicar(r.pontos(), inicio, fim)), false);
                })
                .orElseThrow(RodadaNaoEncontradaException::new);
    }

    private void exigirRegiao(long regionId) {
        if (!regioes.existsById(regionId)) {
            throw new RegiaoNaoEncontradaException();
        }
    }
}
package br.com.neoviaengenharia.clima.app.regiao;

import java.util.Optional;
import org.springframework.stereotype.Component;

/** Validação de região para uso interno (S1-FR-08). Usa o catálogo e só chama a Nimbus se ele tiver vencido. */
@Component
public class ValidacaoRegiao {

    private final CatalogoRegioesService catalogo;

    public ValidacaoRegiao(CatalogoRegioesService catalogo) {
        this.catalogo = catalogo;
    }

    /**
     * Vazio quando a região está ativa; senão o motivo da recusa.
     *
     * @throws FonteIndisponivelException se não houver catálogo e a Nimbus estiver fora
     */
    public Optional<MotivoRecusa> verificar(long regionId) {
        return catalogo.obter().snapshot().regioes().stream()
                .filter(r -> r.regionId() == regionId)
                .findFirst()
                .map(r -> r.inativa() ? Optional.of(MotivoRecusa.INATIVA) : Optional.<MotivoRecusa>empty())
                .orElse(Optional.of(MotivoRecusa.NAO_ENCONTRADA));
    }
}

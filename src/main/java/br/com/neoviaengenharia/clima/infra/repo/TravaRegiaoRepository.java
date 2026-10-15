package br.com.neoviaengenharia.clima.infra.repo;

import br.com.neoviaengenharia.clima.domain.regiao.Regiao;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;

/** Trava por região entre instâncias para a busca do observado (S4-FR-16). */
public interface TravaRegiaoRepository extends Repository<Regiao, Long> {

    /** Lock pessimista na linha da região; exige transação ativa. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "15000"))
    @Query("select r from Regiao r where r.regionId = :regionId")
    Optional<Regiao> travar(long regionId);
}

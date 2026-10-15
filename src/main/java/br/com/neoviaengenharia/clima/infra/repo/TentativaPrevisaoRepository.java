package br.com.neoviaengenharia.clima.infra.repo;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Collection;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface TentativaPrevisaoRepository extends JpaRepository<TentativaPrevisao, Long> {

    /** Lock pessimista nas linhas, em ordem de regionId (evita deadlock); exige transação ativa. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "15000"))
    @Query("select t from TentativaPrevisao t where t.regionId in :ids order by t.regionId")
    List<TentativaPrevisao> travar(@Param("ids") Collection<Long> ids);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = "INSERT INTO clima.tentativa_previsao (region_id, ultima_tentativa_em, ultima_falhou) "
            + "SELECT :id, NULL, 0 WHERE NOT EXISTS "
            + "(SELECT 1 FROM clima.tentativa_previsao WHERE region_id = :id)", nativeQuery = true)
    int inserirSeAusente(@Param("id") long regionId);

    /** Cria a linha da região, se faltar, para que ela possa ser travada. Tolera a corrida entre instâncias. */
    default void garantir(long regionId) {
        try {
            inserirSeAusente(regionId);
        } catch (DataIntegrityViolationException e) {
            // outra instância inseriu a mesma linha entre o NOT EXISTS e o INSERT
        }
    }
}

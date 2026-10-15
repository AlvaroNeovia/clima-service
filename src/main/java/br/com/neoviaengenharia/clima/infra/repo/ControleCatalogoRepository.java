package br.com.neoviaengenharia.clima.infra.repo;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface ControleCatalogoRepository extends JpaRepository<ControleCatalogo, Integer> {

    /** Lock pessimista na linha única de controle; exige transação ativa. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "15000"))
    @Query("select c from ControleCatalogo c where c.id = 1")
    ControleCatalogo travar();

    /**
     * Só o valor de {@code ultimaFalhaEm}, sem trava e sem anexar a entidade ao contexto de persistência
     * (um {@link ControleCatalogo} já gerenciado faria {@link #travar()} devolver o estado antigo). Vazio = sem falha.
     */
    @Query("select c.ultimaFalhaEm from ControleCatalogo c where c.id = 1")
    Optional<Instant> ultimaFalhaEm();
}

package br.com.neoviaengenharia.clima.infra.repo;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuscaCatalogoRepository extends JpaRepository<BuscaCatalogo, Long> {

    Optional<BuscaCatalogo> findTopByOrderByObtidoEmDesc();
}

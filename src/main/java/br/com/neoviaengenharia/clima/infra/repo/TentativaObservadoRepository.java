package br.com.neoviaengenharia.clima.infra.repo;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TentativaObservadoRepository extends JpaRepository<TentativaObservado, TentativaObservadoId> {

    List<TentativaObservado> findAllByRegionIdAndDataHoraGreaterThanEqualAndDataHoraLessThan(
            long regionId, Instant de, Instant ate);
}

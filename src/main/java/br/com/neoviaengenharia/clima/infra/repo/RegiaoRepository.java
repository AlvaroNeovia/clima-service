package br.com.neoviaengenharia.clima.infra.repo;

import br.com.neoviaengenharia.clima.domain.regiao.Regiao;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegiaoRepository extends JpaRepository<Regiao, Long> {

    List<Regiao> findAllByRemovidaFalseOrderByRegionId();
}

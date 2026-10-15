package br.com.neoviaengenharia.foundation.infra.repo;

import java.util.UUID;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import br.com.neoviaengenharia.foundation.domain.ToDo;


@Repository
public interface ToDoRepository extends CrudRepository<ToDo, UUID>{
    
}

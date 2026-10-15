package br.com.neoviaengenharia.foundation.infra.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import br.com.neoviaengenharia.foundation.domain.ToDoList;

@Repository
public interface ToDoListRepository extends JpaRepository<ToDoList, UUID> {
    
}

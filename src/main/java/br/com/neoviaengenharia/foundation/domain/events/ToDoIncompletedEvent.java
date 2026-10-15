package br.com.neoviaengenharia.foundation.domain.events;

import java.util.UUID;

import br.com.neoviaengenharia.foundation.domain.ToDo;
import br.com.neoviaengenharia.foundation.domain.ToDoList;
import br.com.neoviaengenharia.foundation.infra.ddd.DomainEvent;

import java.time.Instant;

import lombok.Getter;

@Getter
public class ToDoIncompletedEvent implements DomainEvent {
    private final UUID toDoId;
    private final Instant occurredOn;

    public ToDoIncompletedEvent(UUID toDoId) {
        this.toDoId = toDoId;
        this.occurredOn = Instant.now();
    }

    public static ToDoIncompletedEvent of(ToDo todo) {
        return new ToDoIncompletedEvent(todo.id());
    }
}

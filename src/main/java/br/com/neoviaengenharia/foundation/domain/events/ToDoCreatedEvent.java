package br.com.neoviaengenharia.foundation.domain.events;
import java.util.UUID;

import br.com.neoviaengenharia.foundation.domain.ToDo;
import br.com.neoviaengenharia.foundation.domain.ToDoList;
import br.com.neoviaengenharia.foundation.infra.ddd.DomainEvent;

import java.time.Instant;

import lombok.Getter;

@Getter
public class ToDoCreatedEvent implements DomainEvent {
    private final UUID toDoId;
    private final UUID listId;
    private final String description;
    private final Instant occurredOn;

    private ToDoCreatedEvent(UUID toDoId, UUID listId, String description) {
        this.toDoId = toDoId;
        this.listId = listId;
        this.description = description;
        this.occurredOn = Instant.now();
    }

    public static ToDoCreatedEvent of(ToDo todo, ToDoList list) {
        return new ToDoCreatedEvent(todo.id(), list.id(), todo.getDescription().getValue());
    }
}


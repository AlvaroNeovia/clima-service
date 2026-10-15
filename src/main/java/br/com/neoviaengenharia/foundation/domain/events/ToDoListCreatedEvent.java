package br.com.neoviaengenharia.foundation.domain.events;
import java.util.UUID;

import br.com.neoviaengenharia.foundation.domain.ToDo;
import br.com.neoviaengenharia.foundation.domain.ToDoList;
import br.com.neoviaengenharia.foundation.infra.ddd.DomainEvent;

import java.time.Instant;

import lombok.Getter;

@Getter
public class ToDoListCreatedEvent implements DomainEvent {
    private final UUID listId;
    private final String description;
    private final Instant occurredOn;

    private ToDoListCreatedEvent(UUID listId, String description) {
        this.listId = listId;
        this.description = description;
        this.occurredOn = Instant.now();
    }

    public static ToDoListCreatedEvent of(ToDoList list) {
        return new ToDoListCreatedEvent(list.id(), list.getDescription().getValue());
    }
}


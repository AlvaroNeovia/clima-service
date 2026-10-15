package br.com.neoviaengenharia.foundation.domain;

import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import br.com.neoviaengenharia.foundation.domain.events.*;
import br.com.neoviaengenharia.foundation.infra.ddd.AbstractAggregateRoot;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Embedded;
import jakarta.persistence.Column;

@Table(name = "todo_list")
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE, force = true)
public class ToDoList extends AbstractAggregateRoot<UUID> {

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "description"))
    private Description description;

    @OneToMany(mappedBy = "list", cascade = CascadeType.ALL)
    @JsonIgnoreProperties("list")
    private List<ToDo> itens;

    private ToDoList(Description description) {
        super(UUID.randomUUID());
        this.description = description;
        this.itens = new ArrayList();
    }

    public void addToDo(ToDo todo) {
        todo.setList(this);
        this.itens.add(todo);
        this.registerEvent(ToDoCreatedEvent.of(todo, this));
    }

    public void removeToDo(ToDo todo) {
        itens.stream()
            .filter(item -> todo.equals(item))
            .findAny()
            .ifPresent(ToDo::delete);
        this.registerEvent(ToDoDeletedEvent.of(todo));
    }

    public void changeToDoDescription(ToDo todo, Description description) {
        itens.stream()
            .filter(item -> todo.equals(item))
            .findAny()
            .ifPresent(item -> item.changeDescription(description));
    }

    public void complete(ToDo todo) {
        itens.stream()
            .filter(item -> todo.equals(item))
            .findAny()
            .ifPresent(ToDo::complete);
        this.registerEvent(ToDoCompletedEvent.of(todo));
    }

    public void incomplete(ToDo todo) {
        itens.stream()
            .filter(item -> todo.equals(item))
            .findAny()
            .ifPresent(ToDo::incomplete);
        this.registerEvent(ToDoIncompletedEvent.of(todo));
    }


    public void changeDescription(Description description) {
        this.description = description;
    }

    public static ToDoList of(Description description) {
        if (Objects.isNull(description)) {
            description = Description.EMPTY;
        }
        return new ToDoList(description);
    }
}

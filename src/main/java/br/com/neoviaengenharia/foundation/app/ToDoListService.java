package br.com.neoviaengenharia.foundation.app;

import static jakarta.persistence.LockModeType.PESSIMISTIC_READ;
import static java.util.Objects.isNull;
import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import br.com.neoviaengenharia.foundation.app.cmd.*;
import br.com.neoviaengenharia.foundation.domain.*;
import br.com.neoviaengenharia.foundation.domain.exceptions.*;
import br.com.neoviaengenharia.foundation.infra.repo.*;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.NonNull;


@Service
@AllArgsConstructor
@Transactional(propagation = REQUIRES_NEW)
public class ToDoListService   {
    private final ToDoRepository toDoRepository;
    private final ToDoListRepository toDoListRepository;

    @NonNull
    @Lock(PESSIMISTIC_READ)
    public void handle(@NonNull @Valid CreateToDoListCommand command) {
            ToDoList todoList = ToDoList.of(Description.of(command.getDescription()));
            toDoListRepository.save(todoList);
    }

    @NonNull
    @Lock(PESSIMISTIC_READ)
    public void handle(@NonNull @Valid AddToDoCommand command) throws NotFoundException {
        ToDoList todoList = toDoListRepository.findById(command.getListId())
                .orElseThrow(() -> new NotFoundException("ToDoList not found"));
        ToDo todo = ToDo.of(Description.of(command.getDescription()));
        todoList.addToDo(todo);
        toDoListRepository.save(todoList);
    }

    @NonNull
    @Lock(PESSIMISTIC_READ)
    public void handle(@NonNull @Valid CompleteToDoCommand command) throws NotFoundException {
        ToDo todo = toDoRepository.findById(command.getToDoId())
                .orElseThrow(() -> new NotFoundException("ToDo not found"));
        todo.complete();
        toDoRepository.save(todo);
    }

    @NonNull
    @Lock(PESSIMISTIC_READ)
    public void handle(@NonNull @Valid IncompleteToDoCommand command) throws NotFoundException {
        ToDo todo = toDoRepository.findById(command.getToDoId())
                .orElseThrow(() -> new NotFoundException("ToDo not found"));
        todo.incomplete();
        toDoRepository.save(todo);
    }

    @NonNull
    @Lock(PESSIMISTIC_READ)
    public void handle(@NonNull @Valid DeleteToDoCommand command) throws NotFoundException {
        ToDo todo = toDoRepository.findById(command.getToDoId())
                .orElseThrow(() -> new NotFoundException("ToDo not found"));
        todo.delete();
        toDoRepository.save(todo);
    }
}

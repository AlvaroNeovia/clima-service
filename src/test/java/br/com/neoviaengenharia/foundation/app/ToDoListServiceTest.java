package br.com.neoviaengenharia.foundation.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import br.com.neoviaengenharia.foundation.app.ToDoListService;
import br.com.neoviaengenharia.foundation.app.cmd.*;
import br.com.neoviaengenharia.foundation.domain.*;
import br.com.neoviaengenharia.foundation.infra.repo.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ToDoListServiceTest {

    @Autowired
    private ToDoListService toDoListService;

    @Autowired
    private ToDoListRepository toDoListRepository;

    @Autowired
    private ToDoRepository toDoRepository;

    @Test
    void testCreateToDoList() {
        CreateToDoListCommand command = new CreateToDoListCommand("Sample List");
        toDoListService.handle(command);
        assertFalse(toDoListRepository.findAll().isEmpty());
    }

    @Test
    void testAddToDo() {
        CreateToDoListCommand createCommand = new CreateToDoListCommand("Sample List");
        toDoListService.handle(createCommand);
        UUID listId = toDoListRepository.findAll().get(0).id();

        AddToDoCommand addCommand = new AddToDoCommand(listId, "Sample ToDo");
        toDoListService.handle(addCommand);
        ToDoList toDoList = toDoListRepository.findById(listId).orElseThrow();
        assertFalse(toDoList.getItens().isEmpty());
    }

    @Test
    void testCompleteToDo() {
        CreateToDoListCommand createCommand = new CreateToDoListCommand("Sample List");
        toDoListService.handle(createCommand);
        UUID listId = toDoListRepository.findAll().get(0).id();

        AddToDoCommand addCommand = new AddToDoCommand(listId, "Sample ToDo");
        toDoListService.handle(addCommand);
        UUID toDoId = toDoListRepository.findById(listId).get().getItens().get(0).id();

        CompleteToDoCommand completeCommand = new CompleteToDoCommand(toDoId);
        toDoListService.handle(completeCommand);

        ToDo toDo = toDoRepository.findById(toDoId).orElseThrow();
        assertEquals(Done.COMPLETED, toDo.getDone());
    }

    @Test
    void testIncompleteToDo() {
        CreateToDoListCommand createCommand = new CreateToDoListCommand("Sample List");
        toDoListService.handle(createCommand);
        UUID listId = toDoListRepository.findAll().get(0).id();

        AddToDoCommand addCommand = new AddToDoCommand(listId, "Sample ToDo");
        toDoListService.handle(addCommand);
        UUID toDoId = toDoListRepository.findById(listId).get().getItens().get(0).id();

        CompleteToDoCommand completeCommand = new CompleteToDoCommand(toDoId);
        toDoListService.handle(completeCommand);
        IncompleteToDoCommand incompleteCommand = new IncompleteToDoCommand(toDoId);
        toDoListService.handle(incompleteCommand);

        ToDo toDo = toDoRepository.findById(toDoId).orElseThrow();
        assertEquals(Done.INCOMPLETED, toDo.getDone());
    }

    @Test
    void testDeleteToDo() {
        CreateToDoListCommand createCommand = new CreateToDoListCommand("Sample List");
        toDoListService.handle(createCommand);
        UUID listId = toDoListRepository.findAll().get(0).id();

        AddToDoCommand addCommand = new AddToDoCommand(listId, "Sample ToDo");
        toDoListService.handle(addCommand);
        UUID toDoId = toDoListRepository.findById(listId).get().getItens().get(0).id();

        DeleteToDoCommand deleteCommand = new DeleteToDoCommand(toDoId);
        toDoListService.handle(deleteCommand);

        ToDo toDo = toDoRepository.findById(toDoId).orElseThrow();
        assertTrue(toDo.isDeleted());
    }
}
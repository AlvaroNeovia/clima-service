package br.com.neoviaengenharia.foundation.domain;

import org.junit.jupiter.api.Test;

import br.com.neoviaengenharia.foundation.domain.Description;
import br.com.neoviaengenharia.foundation.domain.Done;
import br.com.neoviaengenharia.foundation.domain.ToDo;
import br.com.neoviaengenharia.foundation.domain.ToDoList;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;

class ToDoListTest {

    @Test
    void testCreateToDoList() {
        ToDoList todoList = ToDoList.of(Description.of("Sample List"));
        assertNotNull(todoList);
        assertEquals("Sample List", todoList.getDescription().getValue());
        assertTrue(todoList.getItens().isEmpty());
    }

    @Test
    void testAddToDo() {
        ToDoList todoList = ToDoList.of(Description.of("Sample List"));
        ToDo todo = ToDo.of(Description.of("Sample ToDo"));
        todoList.addToDo(todo);
        assertEquals(1, todoList.getItens().size());
        assertTrue(todoList.getItens().contains(todo));
    }

    @Test
    void testRemoveToDo() {
        ToDoList todoList = ToDoList.of(Description.of("Sample List"));
        ToDo todo = ToDo.of(Description.of("Sample ToDo"));
        todoList.addToDo(todo);
        todoList.removeToDo(todo);
        assertTrue(todo.isDeleted());
    }

    @Test
    void testChangeToDoDescription() {
        ToDoList todoList = ToDoList.of(Description.of("Sample List"));
        ToDo todo = ToDo.of(Description.of("Sample ToDo"));
        todoList.addToDo(todo);
        todoList.changeToDoDescription(todo, Description.of("Updated ToDo"));
        assertEquals("Updated ToDo", todo.getDescription().getValue());
    }

    @Test
    void testCompleteToDo() {
        ToDoList todoList = ToDoList.of(Description.of("Sample List"));
        ToDo todo = ToDo.of(Description.of("Sample ToDo"));
        todoList.addToDo(todo);
        todoList.complete(todo);
        assertEquals(Done.COMPLETED, todo.getDone());
    }

    @Test
    void testIncompleteToDo() {
        ToDoList todoList = ToDoList.of( Description.of("Sample List"));
        ToDo todo = ToDo.of(Description.of("Sample ToDo"));
        todoList.addToDo(todo);
        todoList.complete(todo);
        todoList.incomplete(todo);
        assertEquals(Done.INCOMPLETED, todo.getDone());
    }

    @Test
    void testChangeListDescription() {
        ToDoList todoList = ToDoList.of(Description.of("Sample List"));
        todoList.changeDescription( Description.of("Updated List"));
        assertEquals("Updated List", todoList.getDescription().getValue());
    }
}

package br.com.neoviaengenharia.foundation.domain;

import org.junit.jupiter.api.Test;

import br.com.neoviaengenharia.foundation.domain.Description;
import br.com.neoviaengenharia.foundation.domain.Done;
import br.com.neoviaengenharia.foundation.domain.ToDo;

import static org.junit.jupiter.api.Assertions.*;

public class ToDoTest {

    @Test
    void testCreateToDo() {
        ToDo todo = ToDo.of(Description.of("Sample ToDo"));
        assertNotNull(todo);
        assertEquals("Sample ToDo", todo.getDescription().getValue());
        assertEquals(Done.INCOMPLETED, todo.getDone());
        assertFalse(todo.isDeleted());
    }

    @Test
    void testCompleteToDo() {
        ToDo todo = ToDo.of(Description.of("Sample ToDo"));
        todo.complete();
        assertEquals(Done.COMPLETED, todo.getDone());
    }

    @Test
    void testIncompleteToDo() {
        ToDo todo = ToDo.of(Description.of("Sample ToDo"));
        todo.complete();
        todo.incomplete();
        assertEquals(Done.INCOMPLETED, todo.getDone());
    }

    @Test
    void testDeleteToDo() {
        ToDo todo = ToDo.of(Description.of("Sample ToDo"));
        todo.delete();
        assertTrue(todo.isDeleted());
    }

    @Test
    void testChangeDescription() {
        ToDo todo = ToDo.of(Description.of("Sample ToDo"));
        todo.changeDescription(Description.of("Updated ToDo"));
        assertEquals("Updated ToDo", todo.getDescription().getValue());
    }
}

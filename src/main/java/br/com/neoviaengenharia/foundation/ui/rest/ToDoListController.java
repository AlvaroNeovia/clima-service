package br.com.neoviaengenharia.foundation.ui.rest;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.NonNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.neoviaengenharia.foundation.app.ToDoListService;
import br.com.neoviaengenharia.foundation.app.cmd.*;

@RestController
@RequestMapping("/api/todo-lists")
@AllArgsConstructor
public class ToDoListController {
    private final ToDoListService toDoListService;

    @PostMapping
    @Operation(summary = "Cadastra uma nova ToDoList", description = "Cadastra uma nova ToDoList.")
    @ApiResponse(responseCode = "201", description = "ToDoList cadastrada com sucesso", content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "400", description = "Requisição inválida")
    @ApiResponse(responseCode = "500", description = "Erro interno no servidor")
    public ResponseEntity<Void> create(@RequestBody CreateToDoListCommand command) {
        toDoListService.handle(command);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @PostMapping("/{listId}/todos")
    @Operation(summary = "Cadastra ToDo na ToDoList", description = "Cadastra ToDo na ToDoList.")
    @ApiResponse(responseCode = "201", description = "Equipes cadastrada com sucesso", content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "400", description = "Requisição inválida")
    @ApiResponse(responseCode = "500", description = "Erro interno no servidor")
    public ResponseEntity<Void> addToDo(@PathVariable UUID listId, @RequestBody AddToDoCommand command) {
        toDoListService.handle(command);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @PostMapping("/todos/complete")
    @Operation(summary = "Marca ToDo como completo", description = "Marca ToDo como completo.")
    @ApiResponse(responseCode = "200", description = "ToDo marcado como completo com sucesso", content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "400", description = "Requisição inválida")
    @ApiResponse(responseCode = "500", description = "Erro interno no servidor")
    public ResponseEntity<Void> complete(@RequestBody CompleteToDoCommand command) {
        toDoListService.handle(command);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @PostMapping("/todos/incomplete")
    @Operation(summary = "Marca ToDo como incompleto", description = "Marca ToDo como incompleto.")
    @ApiResponse(responseCode = "200", description = "ToDo marcado como incompleto com sucesso", content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "400", description = "Requisição inválida")
    @ApiResponse(responseCode = "500", description = "Erro interno no servidor")
    public ResponseEntity<Void> incomplete(@RequestBody IncompleteToDoCommand command) {
        toDoListService.handle(command);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @PostMapping("/todos/delete")
    @Operation(summary = "Deleta ToDo", description = "Marca ToDo como deletado.")
    @ApiResponse(responseCode = "200", description = "ToDo deletado com sucesso", content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "400", description = "Requisição inválida")
    @ApiResponse(responseCode = "500", description = "Erro interno no servidor")
    public ResponseEntity<Void> delete(@RequestBody DeleteToDoCommand command) {
        toDoListService.handle(command);
        return new ResponseEntity<>(HttpStatus.OK);
    }
}

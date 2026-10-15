package br.com.neoviaengenharia.foundation.app.cmd;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Data
@Builder
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class DeleteToDoCommand {
    @NotNull(message = "O parâmetro 'toDoId' é obrigatório!")
    private UUID toDoId;
}

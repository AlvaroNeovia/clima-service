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
public class CreateToDoListCommand {
    @NotNull(message = "O parâmetro 'description' é obrigatório!")
    private String description;
}
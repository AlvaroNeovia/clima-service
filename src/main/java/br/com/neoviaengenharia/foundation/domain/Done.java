package br.com.neoviaengenharia.foundation.domain;

import static lombok.AccessLevel.PRIVATE;

import br.com.neoviaengenharia.foundation.infra.ddd.ValueObject;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@EqualsAndHashCode(of = "value")
@AllArgsConstructor(access = PRIVATE)
public final class Done implements ValueObject {
    public static final Done INCOMPLETED = of(false);
    public static final Done COMPLETED = of(true);

    private final Boolean value;

    private static Done of(boolean value) {
        return new Done(false);
    }
}

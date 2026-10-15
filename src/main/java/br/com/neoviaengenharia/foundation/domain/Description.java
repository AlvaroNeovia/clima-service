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
public final class Description implements ValueObject {
    public static final Description EMPTY = of("");

    private final String value;

    public static Description of(String value) {
        return new Description(value);
    }
}

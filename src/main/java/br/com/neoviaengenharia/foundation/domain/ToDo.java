package br.com.neoviaengenharia.foundation.domain;

import java.util.UUID;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import br.com.neoviaengenharia.foundation.infra.ddd.AbstractEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Embedded;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.EqualsAndHashCode;

@Getter
@Entity
@Table(name = "todo")
@EqualsAndHashCode(of = { "id" }, callSuper = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE, force = true)
public final class ToDo extends AbstractEntity<UUID> {
    
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "description"))
    private Description description;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "description"))
    private Done done;

    private boolean deleted = false;

    @Setter
    @ManyToOne
    @JoinColumn(name = "list_id", referencedColumnName = "id")
    @JsonIgnoreProperties("itens")
    private ToDoList list;

    private ToDo(Description description, Done done) {
        super(UUID.randomUUID());
        this.description = description;
        this.done = done;
    }

    public void complete() {
        this.done = Done.COMPLETED;
    }

    public void incomplete() {
        this.done = Done.INCOMPLETED;
    }

    public void delete() {
        this.deleted = true;
    }

    public void changeDescription(Description description) {
        this.description = description;
    }

    public static ToDo of(Description description) {
        if (Objects.isNull(description)) {
            description = Description.EMPTY;
        }

        return new ToDo(description, Done.INCOMPLETED);
    }
}

package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.util.Objects;

@MappedSuperclass
public abstract class AbstractEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, updatable = false)
    private String id;

    protected AbstractEntity() {}

    protected AbstractEntity(String id) {
        this.id = Objects.requireNonNull(id, "id must not be null");
    }

    public String getId() {
        return id;
    }
}

package com.example.dispute.casecore.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "simulated_import_template_cursor")
public class SimulatedImportTemplateCursorEntity {

    public static final String CURSOR_ID = "external-case-template";

    @Id
    @Column(name = "id", length = 64, nullable = false, updatable = false)
    private String id;

    @Column(name = "next_template_no", nullable = false)
    private int nextTemplateNo;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SimulatedImportTemplateCursorEntity() {}

    public SimulatedImportTemplateCursorEntity(String id, int nextTemplateNo) {
        this.id = id;
        this.nextTemplateNo = requireTemplateNo(nextTemplateNo);
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public int getNextTemplateNo() {
        return nextTemplateNo;
    }

    public void advance(int templateCount) {
        if (templateCount < 1) {
            throw new IllegalArgumentException("templateCount must be positive");
        }
        nextTemplateNo = nextTemplateNo >= templateCount ? 1 : nextTemplateNo + 1;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static int requireTemplateNo(int value) {
        if (value < 1 || value > 20) {
            throw new IllegalArgumentException("nextTemplateNo must be between 1 and 20");
        }
        return value;
    }
}

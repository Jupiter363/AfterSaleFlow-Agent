package com.example.dispute.room.application;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Fixed public result for the repeat-safe Intake infrastructure transition. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntakeInfrastructurePreparationView(String schemaVersion, String status) {

    public static final String SCHEMA_VERSION = "intake-infrastructure-preparation.v1";
    public static final String READY = "READY";
    public static final String NOT_REQUIRED = "NOT_REQUIRED";

    public IntakeInfrastructurePreparationView {
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || (!READY.equals(status) && !NOT_REQUIRED.equals(status))) {
            throw new IllegalArgumentException(
                    "Intake infrastructure preparation view is invalid");
        }
    }

    public static IntakeInfrastructurePreparationView ready() {
        return new IntakeInfrastructurePreparationView(SCHEMA_VERSION, READY);
    }

    public static IntakeInfrastructurePreparationView notRequired() {
        return new IntakeInfrastructurePreparationView(SCHEMA_VERSION, NOT_REQUIRED);
    }
}

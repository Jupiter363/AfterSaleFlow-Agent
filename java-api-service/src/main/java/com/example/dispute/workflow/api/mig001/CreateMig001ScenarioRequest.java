package com.example.dispute.workflow.api.mig001;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateMig001ScenarioRequest(
        @NotBlank
                @Pattern(regexp = "[0-9a-f]{32}")
                String scenarioId) {}

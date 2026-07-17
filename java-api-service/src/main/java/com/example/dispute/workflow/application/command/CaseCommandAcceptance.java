package com.example.dispute.workflow.application.command;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CaseCommandAcceptance(
        CaseCommandRef command,
        String commandStatus,
        Instant acceptedAt,
        boolean idempotentReplay) {}

package com.example.dispute.hearing.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.domain.SettlementStatus;
import java.time.Instant;
import java.util.List;

public record SettlementView(
        String settlementId,
        String caseId,
        int version,
        SettlementStatus status,
        ActorRole proposedByRole,
        String proposalText,
        String proposalJson,
        List<ActorRole> confirmedRoles,
        Instant createdAt) {}

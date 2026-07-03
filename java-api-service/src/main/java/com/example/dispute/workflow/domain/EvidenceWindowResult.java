package com.example.dispute.workflow.domain;

import java.util.List;

public record EvidenceWindowResult(String caseId, String stopReason, List<String> completedRoles) {}

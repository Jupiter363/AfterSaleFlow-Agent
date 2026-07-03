package com.example.dispute.evidence.application;

import java.util.List;
import java.util.Map;

public record FrozenEvidenceDossierView(
        String caseId,
        String dossierId,
        int dossierVersion,
        String dossierStatus,
        Map<String, Object> summary,
        List<Map<String, Object>> timeline,
        List<Map<String, Object>> matrix,
        List<String> evidenceIds) {}

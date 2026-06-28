package com.example.dispute.caseintake.application;

import java.util.List;

public record CasePageView(
        List<CaseView> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {}

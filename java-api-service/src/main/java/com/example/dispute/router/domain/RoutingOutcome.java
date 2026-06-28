package com.example.dispute.router.domain;

import com.example.dispute.domain.model.RouteType;

public record RoutingOutcome(
        RouteType routeType, String reasonCode, boolean requiresAdditionalEvidence) {}

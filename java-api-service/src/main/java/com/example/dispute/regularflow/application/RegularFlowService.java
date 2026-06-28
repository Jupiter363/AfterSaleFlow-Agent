package com.example.dispute.regularflow.application;

import org.springframework.stereotype.Service;

@Service
public class RegularFlowService {

    public RegularFlowConclusion conclude(String caseType) {
        return switch (caseType) {
            case "LOGISTICS_QUERY", "DELIVERY_STATUS" ->
                    new RegularFlowConclusion(
                            "LOGISTICS_STATUS_READY",
                            "Order and logistics status are ready for remedy planning.",
                            java.util.List.of(
                                    "QUERY_LOGISTICS", "PREPARE_STATUS_NOTICE"));
            case "DELIVERY_REMINDER" ->
                    new RegularFlowConclusion(
                            "FULFILLMENT_REMINDER_RECOMMENDED",
                            "A fulfillment reminder is recommended for approval.",
                            java.util.List.of("CREATE_FULFILLMENT_REMINDER"));
            default ->
                    new RegularFlowConclusion(
                            "REGULAR_SERVICE_TICKET_RECOMMENDED",
                            "A regular fulfillment service ticket is recommended.",
                            java.util.List.of("CREATE_SERVICE_TICKET"));
        };
    }
}

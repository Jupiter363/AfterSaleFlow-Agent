package com.example.dispute.casecore.application;

import java.util.List;

public interface ExternalDisputeSimulationClient {

    List<SimulatedExternalDispute> simulate(
            SimulateExternalImportCommand command,
            String traceId,
            String requestId);
}

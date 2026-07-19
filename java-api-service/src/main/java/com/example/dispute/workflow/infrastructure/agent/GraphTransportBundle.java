package com.example.dispute.workflow.infrastructure.agent;

import java.util.Objects;

/** Command and reconciliation transports created from one security context and one proof. */
public final class GraphTransportBundle {

    private final GraphCommandHttpTransport commandTransport;
    private final GraphReconciliationHttpTransport reconciliationTransport;
    private final GraphTransportSecurityProof transportProof;

    GraphTransportBundle(
            GraphCommandHttpTransport commandTransport,
            GraphReconciliationHttpTransport reconciliationTransport,
            GraphTransportSecurityProof transportProof) {
        this.commandTransport = Objects.requireNonNull(commandTransport, "commandTransport");
        this.reconciliationTransport =
                Objects.requireNonNull(reconciliationTransport, "reconciliationTransport");
        this.transportProof = Objects.requireNonNull(transportProof, "transportProof");
        if (commandTransport.transportProof() != transportProof
                || reconciliationTransport.transportProof() != transportProof
                || transportProof.mode() == GraphTransportSecurityProof.Mode.UNVERIFIED) {
            throw new IllegalArgumentException(
                    "Graph transport bundle must share one factory-issued proof");
        }
    }

    public GraphCommandHttpTransport commandTransport() {
        return commandTransport;
    }

    public GraphReconciliationHttpTransport reconciliationTransport() {
        return reconciliationTransport;
    }

    public GraphTransportSecurityProof transportProof() {
        return transportProof;
    }
}

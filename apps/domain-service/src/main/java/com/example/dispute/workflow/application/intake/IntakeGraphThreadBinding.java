package com.example.dispute.workflow.application.intake;

import java.util.Objects;

/** Domain-side registration binding; fencing is intentionally not part of the wire hash. */
public record IntakeGraphThreadBinding(
        IntakePrivateThreadRegistration registration, long fencingToken) {

    public IntakeGraphThreadBinding {
        registration = Objects.requireNonNull(registration, "registration must not be null");
        IntakeContractSupport.positive(fencingToken, "fencingToken");
        registration.requireCanonicalHash();
    }
}

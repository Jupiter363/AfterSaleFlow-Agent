package com.example.dispute.workflow.targete2e.ingress;

import java.util.Objects;

public record IntakeIngressSelection(Route route, TargetIntakeActivationGrant targetGrant) {

    public IntakeIngressSelection {
        Objects.requireNonNull(route, "route must not be null");
        if ((route == Route.LEGACY) != (targetGrant == null)) {
            throw new IllegalArgumentException("only the target route carries an activation grant");
        }
    }

    public static IntakeIngressSelection legacy() {
        return new IntakeIngressSelection(Route.LEGACY, null);
    }

    public static IntakeIngressSelection target(TargetIntakeActivationGrant grant) {
        return new IntakeIngressSelection(
                Route.TARGET_E2E_CANDIDATE,
                Objects.requireNonNull(grant, "grant must not be null"));
    }

    public boolean isTarget() {
        return route == Route.TARGET_E2E_CANDIDATE;
    }

    public enum Route {
        LEGACY,
        TARGET_E2E_CANDIDATE
    }
}

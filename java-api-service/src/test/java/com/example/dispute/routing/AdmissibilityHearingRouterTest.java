package com.example.dispute.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.routing.domain.AdmissibilityContext;
import com.example.dispute.routing.domain.AdmissibilityHearingRouter;
import com.example.dispute.routing.domain.HearingRoute;
import org.junit.jupiter.api.Test;

class AdmissibilityHearingRouterTest {

    private final AdmissibilityHearingRouter router =
            new AdmissibilityHearingRouter();

    @Test
    void transfersRequestsThatAreNotFulfillmentDisputes() {
        var outcome =
                router.decide(
                        new AdmissibilityContext(
                                false,
                                RiskLevel.LOW,
                                true,
                                false,
                                true));

        assertThat(outcome.route()).isEqualTo(HearingRoute.TRANSFERRED);
        assertThat(outcome.terminalInDisputeSystem()).isTrue();
        assertThat(outcome.requiresAdditionalEvidence()).isFalse();
    }

    @Test
    void selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence() {
        var outcome =
                router.decide(
                        new AdmissibilityContext(
                                true,
                                RiskLevel.LOW,
                                true,
                                false,
                                true));

        assertThat(outcome.route()).isEqualTo(HearingRoute.SIMPLE_HEARING);
        assertThat(outcome.terminalInDisputeSystem()).isFalse();
        assertThat(outcome.requiresDeliberation()).isFalse();
    }

    @Test
    void selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules() {
        assertThat(
                        router.decide(
                                        new AdmissibilityContext(
                                                true,
                                                RiskLevel.MEDIUM,
                                                true,
                                                true,
                                                true))
                                .route())
                .isEqualTo(HearingRoute.FULL_HEARING);
        assertThat(
                        router.decide(
                                        new AdmissibilityContext(
                                                true,
                                                RiskLevel.HIGH,
                                                true,
                                                false,
                                                true))
                                .requiresDeliberation())
                .isTrue();
        assertThat(
                        router.decide(
                                        new AdmissibilityContext(
                                                true,
                                                RiskLevel.LOW,
                                                false,
                                                false,
                                                true))
                                .requiresAdditionalEvidence())
                .isTrue();
        assertThat(
                        router.decide(
                                        new AdmissibilityContext(
                                                true,
                                                RiskLevel.LOW,
                                                true,
                                                false,
                                                false))
                                .route())
                .isEqualTo(HearingRoute.FULL_HEARING);
    }
}

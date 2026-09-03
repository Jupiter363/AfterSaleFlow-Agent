package com.example.dispute.workflow.shadow.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.shadow.hearing.HearingNoFormalSinkGuard.SinkDisposition;
import com.example.dispute.workflow.shadow.hearing.HearingSignedSyntheticAdmissionService.AdmissionContext;
import com.example.dispute.workflow.shadow.hearing.HearingSyntheticAdmissionClaims.ScopeKind;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HearingSignedSyntheticAdmissionServiceTest {

    private final HearingSyntheticAdmissionTestFixture fixture =
            new HearingSyntheticAdmissionTestFixture();
    private final Es256HearingSyntheticAdmissionVerifier verifier =
            new Es256HearingSyntheticAdmissionVerifier(fixture.trustSet(), fixture.CLOCK);
    private final HearingSignedSyntheticAdmissionService service =
            new HearingSignedSyntheticAdmissionService(
                    fixture.selector(), verifier, new HearingNoFormalSinkGuard());

    @Test
    void admitsExactSignedFixtureAsClosedComparisonReceipt() {
        var receipt = service.admit(fixture.sign(fixture.claims()), fixture.context());

        assertThat(receipt.fixtureId()).isEqualTo(fixture.FIXTURE);
        assertThat(receipt.scopeKind()).isEqualTo(ScopeKind.ACTOR);
        assertThat(receipt.scopeHash()).matches("[0-9a-f]{64}");
        assertThat(receipt.envelopeHash()).matches("[0-9a-f]{64}");
        assertThat(receipt.labels()).containsOnlyKeys("suite", "scenario", "result_class");
        assertThat(receipt.sinkDisposition()).isEqualTo(SinkDisposition.NO_FORMAL_SINK);
    }

    @Test
    void signatureTamperAndUnknownClaimsFailClosed() {
        String token = fixture.sign(fixture.claims());
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(HearingSyntheticAdmissionException.class);

        ObjectNode extra = fixture.claims().put("real_case_resolver", "forbidden");
        assertThatThrownBy(() -> verifier.verify(fixture.sign(extra)))
                .isInstanceOfSatisfying(
                        HearingSyntheticAdmissionException.class,
                        error -> assertThat(error.code()).isEqualTo("ADMISSION_EVIDENCE_INVALID"));
    }

    @Test
    void scopeAndSelectionSubstitutionFailClosed() {
        AdmissionContext wrongScope = new AdmissionContext(
                fixture.FIXTURE,
                fixture.TENANT,
                fixture.CASE,
                fixture.EPOCH,
                ScopeKind.SHARED,
                "shared-system",
                fixture.HASH);
        assertThatThrownBy(() -> service.admit(fixture.sign(fixture.claims()), wrongScope))
                .isInstanceOfSatisfying(
                        HearingSyntheticAdmissionException.class,
                        error -> assertThat(error.code()).isEqualTo("ADMISSION_SCOPE_MISMATCH"));

        ObjectNode selectionDrift = fixture.claims().put("selection_hash", "b".repeat(64));
        assertThatThrownBy(() -> service.admit(fixture.sign(selectionDrift), fixture.context()))
                .isInstanceOfSatisfying(
                        HearingSyntheticAdmissionException.class,
                        error -> assertThat(error.code()).isEqualTo("ADMISSION_SELECTION_MISMATCH"));
    }

    @Test
    void labelsAreClosedAndBounded() {
        ObjectNode unknown = fixture.claims();
        unknown.withObject("labels").put("case_id", "unbounded-cardinality");
        assertThatThrownBy(() -> verifier.verify(fixture.sign(unknown)))
                .isInstanceOf(HearingSyntheticAdmissionException.class);

        assertThatThrownBy(() -> new HearingSyntheticAdmissionClaims(
                        HearingSyntheticAdmissionClaims.SCHEMA_VERSION,
                        HearingSyntheticAdmissionClaims.ISSUER,
                        HearingSyntheticAdmissionClaims.AUDIENCE,
                        HearingSyntheticAdmissionClaims.SUBJECT,
                        "synthetic-token-1",
                        fixture.NOW.minusSeconds(1).getEpochSecond(),
                        fixture.NOW.minusSeconds(1).getEpochSecond(),
                        fixture.NOW.plusSeconds(10).getEpochSecond(),
                        "HEARING",
                        "SHADOW",
                        fixture.FIXTURE,
                        fixture.TENANT,
                        fixture.CASE,
                        fixture.EPOCH,
                        "PINNED_NEW_EPOCH",
                        ScopeKind.ACTOR,
                        fixture.SCOPE,
                        HearingSyntheticAdmissionClaims.calculateScopeHash(
                                fixture.FIXTURE,
                                fixture.TENANT,
                                fixture.CASE,
                                fixture.EPOCH,
                                ScopeKind.ACTOR,
                                fixture.SCOPE),
                        fixture.claims().get("selection_hash").textValue(),
                        fixture.HASH,
                        Map.of("scenario", "x".repeat(65))))
                .isInstanceOf(HearingSyntheticAdmissionException.class);
    }
}

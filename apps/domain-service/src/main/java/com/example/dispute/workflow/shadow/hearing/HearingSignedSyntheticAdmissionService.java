package com.example.dispute.workflow.shadow.hearing;

import com.example.dispute.workflow.config.HearingEpochSelector;
import com.example.dispute.workflow.config.HearingEpochSelector.EpochAdmission;
import com.example.dispute.workflow.config.HearingEpochSelector.SelectionDecision;
import com.example.dispute.workflow.config.HearingEpochSelector.ShadowAuthorization;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.shadow.hearing.Es256HearingSyntheticAdmissionVerifier.VerifiedToken;
import com.example.dispute.workflow.shadow.hearing.HearingSyntheticAdmissionClaims.ScopeKind;
import java.util.Map;
import java.util.Objects;

/** Admits an inert comparison fixture without any real-case lookup or formal callback surface. */
public final class HearingSignedSyntheticAdmissionService {

    private final HearingEpochSelector selector;
    private final Es256HearingSyntheticAdmissionVerifier verifier;
    private final HearingNoFormalSinkGuard noFormalSinkGuard;

    public HearingSignedSyntheticAdmissionService(
            HearingEpochSelector selector,
            Es256HearingSyntheticAdmissionVerifier verifier,
            HearingNoFormalSinkGuard noFormalSinkGuard) {
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.noFormalSinkGuard = Objects.requireNonNull(noFormalSinkGuard, "guard must not be null");
    }

    public AdmissionReceipt admit(String compactJws, AdmissionContext context) {
        Objects.requireNonNull(context, "context must not be null");
        VerifiedToken verified = verifier.verify(compactJws);
        HearingSyntheticAdmissionClaims claims = verified.claims();
        requireExactContext(context, claims);

        SelectionDecision selection = selector.decide(
                RoomType.HEARING,
                context.tenantSurrogate(),
                context.caseId(),
                EpochAdmission.PINNED_NEW_EPOCH,
                ShadowAuthorization.JAVA_SIGNED_SYNTHETIC_FIXTURE);
        if (selection.writerMode() != WriterMode.SHADOW
                || !claims.selectionHash().equals(selection.cohortKeyHash())) {
            throw rejected("ADMISSION_SELECTION_MISMATCH", "signed fixture is not selected for shadow");
        }
        HearingNoFormalSinkGuard.Decision guardDecision = noFormalSinkGuard.verify(
                HearingNoFormalSinkGuard.AssemblyContract.signedSynthetic());
        return new AdmissionReceipt(
                claims.fixtureId(),
                claims.scopeKind(),
                claims.scopeHash(),
                verified.keyId(),
                verified.envelopeHash(),
                verified.claimsHash(),
                claims.expectedTraceHash(),
                claims.labels(),
                selection.cohortPolicyVersion(),
                guardDecision.sinkDisposition());
    }

    private static void requireExactContext(
            AdmissionContext context, HearingSyntheticAdmissionClaims claims) {
        boolean matches = context.fixtureId().equals(claims.fixtureId())
                && context.tenantSurrogate().equals(claims.tenantSurrogate())
                && context.caseId().equals(claims.caseId())
                && context.roomEpoch() == claims.roomEpoch()
                && context.scopeKind() == claims.scopeKind()
                && context.scopeId().equals(claims.scopeId())
                && context.expectedTraceHash().equals(claims.expectedTraceHash());
        if (!matches) {
            throw rejected(
                    "ADMISSION_SCOPE_MISMATCH",
                    "signed claims do not bind the exact Java synthetic fixture context");
        }
    }

    private static HearingSyntheticAdmissionException rejected(String code, String message) {
        return new HearingSyntheticAdmissionException(code, message);
    }

    public record AdmissionContext(
            String fixtureId,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            ScopeKind scopeKind,
            String scopeId,
            String expectedTraceHash) {

        public AdmissionContext {
            Objects.requireNonNull(fixtureId, "fixtureId must not be null");
            Objects.requireNonNull(tenantSurrogate, "tenantSurrogate must not be null");
            Objects.requireNonNull(caseId, "caseId must not be null");
            Objects.requireNonNull(scopeKind, "scopeKind must not be null");
            Objects.requireNonNull(scopeId, "scopeId must not be null");
            Objects.requireNonNull(expectedTraceHash, "expectedTraceHash must not be null");
        }
    }

    /** Closed data receipt. It deliberately carries no executable callback or domain object. */
    public record AdmissionReceipt(
            String fixtureId,
            ScopeKind scopeKind,
            String scopeHash,
            String signingKeyId,
            String envelopeHash,
            String claimsHash,
            String expectedTraceHash,
            Map<String, String> labels,
            String cohortPolicyVersion,
            HearingNoFormalSinkGuard.SinkDisposition sinkDisposition) {

        public AdmissionReceipt {
            labels = Map.copyOf(labels);
            if (sinkDisposition != HearingNoFormalSinkGuard.SinkDisposition.NO_FORMAL_SINK) {
                throw new IllegalArgumentException("synthetic receipt cannot carry a formal sink");
            }
        }
    }
}

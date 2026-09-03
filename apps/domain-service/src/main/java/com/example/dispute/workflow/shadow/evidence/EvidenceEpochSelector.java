package com.example.dispute.workflow.shadow.evidence;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.RuntimeMode;
import java.util.Objects;

/**
 * Fail-closed selector for the closed Evidence engineering runtime.
 *
 * <p>Selection never allocates an Evidence Workflow or starts a timer. The existing Java-owned
 * legacy timer remains the only deadline authority while signed synthetic shadow work is observed.
 */
public final class EvidenceEpochSelector {

    public static final String SELECTION_VERSION = "evidence-epoch-selection.v1";

    private final RuntimeMode configuredMode;

    public EvidenceEpochSelector(RuntimeMode configuredMode) {
        this.configuredMode = Objects.requireNonNull(configuredMode, "configuredMode must not be null");
        if (configuredMode != RuntimeMode.DISABLED && configuredMode != RuntimeMode.SHADOW) {
            throw new IllegalArgumentException(
                    "Evidence runtime is limited to DISABLED or signed synthetic SHADOW");
        }
    }

    public SelectionDecision decide(SelectionRequest request) {
        if (configuredMode == RuntimeMode.DISABLED) {
            return SelectionDecision.disabled(DecisionReason.RUNTIME_DISABLED);
        }
        if (request == null) {
            return SelectionDecision.disabled(DecisionReason.MISSING_SELECTION_CONTEXT);
        }
        if (!SELECTION_VERSION.equals(request.selectionVersion())) {
            return SelectionDecision.disabled(DecisionReason.UNKNOWN_SELECTION_VERSION);
        }
        if (request.roomType() != RoomType.EVIDENCE) {
            return SelectionDecision.disabled(DecisionReason.NON_EVIDENCE_ROOM);
        }
        if (request.authorization() == null
                || request.authorization() == TrafficAuthorization.UNKNOWN) {
            return SelectionDecision.disabled(DecisionReason.UNKNOWN_OR_MISSING_AUTHORIZATION);
        }
        if (request.authorization() == TrafficAuthorization.UNSIGNED_SYNTHETIC) {
            return SelectionDecision.disabled(DecisionReason.UNSIGNED_SYNTHETIC_FORBIDDEN);
        }
        if (request.authorization() == TrafficAuthorization.JAVA_SIGNED_REAL_CASE) {
            return SelectionDecision.disabled(DecisionReason.REAL_CASE_FORBIDDEN);
        }
        if (isBlank(request.tenantSurrogate()) || isBlank(request.caseId())) {
            return SelectionDecision.disabled(DecisionReason.MISSING_JAVA_CASE_IDENTITY);
        }
        if (request.selectedRoomEpoch() < 1
                || request.selectedFencingToken() < 1
                || request.currentJavaRoomEpoch() < 1
                || request.currentJavaFencingToken() < 1) {
            return SelectionDecision.disabled(DecisionReason.MALFORMED_JAVA_AUTHORITY);
        }
        if (request.selectedRoomEpoch() != request.currentJavaRoomEpoch()
                || request.selectedFencingToken() != request.currentJavaFencingToken()) {
            return SelectionDecision.disabled(DecisionReason.STALE_JAVA_AUTHORITY);
        }
        if (request.activeLegacyTimerCount() != 1) {
            return SelectionDecision.disabled(DecisionReason.LEGACY_TIMER_INVARIANT_VIOLATION);
        }
        return SelectionDecision.signedSyntheticShadow(
                request.currentJavaRoomEpoch(), request.currentJavaFencingToken());
    }

    public RuntimeMode select(SelectionRequest request) {
        return decide(request).runtimeMode();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum TrafficAuthorization {
        JAVA_SIGNED_SYNTHETIC,
        UNSIGNED_SYNTHETIC,
        JAVA_SIGNED_REAL_CASE,
        UNKNOWN
    }

    public enum DecisionReason {
        RUNTIME_DISABLED,
        MISSING_SELECTION_CONTEXT,
        UNKNOWN_SELECTION_VERSION,
        NON_EVIDENCE_ROOM,
        UNKNOWN_OR_MISSING_AUTHORIZATION,
        UNSIGNED_SYNTHETIC_FORBIDDEN,
        REAL_CASE_FORBIDDEN,
        MISSING_JAVA_CASE_IDENTITY,
        MALFORMED_JAVA_AUTHORITY,
        STALE_JAVA_AUTHORITY,
        LEGACY_TIMER_INVARIANT_VIOLATION,
        AUTHENTICATED_SIGNED_SYNTHETIC_SHADOW
    }

    public record SelectionRequest(
            String selectionVersion,
            RoomType roomType,
            String tenantSurrogate,
            String caseId,
            long selectedRoomEpoch,
            long selectedFencingToken,
            long currentJavaRoomEpoch,
            long currentJavaFencingToken,
            int activeLegacyTimerCount,
            TrafficAuthorization authorization) {}

    public record SelectionDecision(
            RuntimeMode runtimeMode,
            DecisionReason reason,
            long javaRoomEpoch,
            long javaFencingToken,
            int legacyTimerStartCount,
            boolean formalSinkReachable) {

        public SelectionDecision {
            Objects.requireNonNull(runtimeMode, "runtimeMode must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            if (runtimeMode != RuntimeMode.DISABLED && runtimeMode != RuntimeMode.SHADOW) {
                throw new IllegalArgumentException("selector cannot emit an active runtime");
            }
            if (legacyTimerStartCount != 0) {
                throw new IllegalArgumentException("Evidence selection cannot start a legacy timer");
            }
            if (formalSinkReachable) {
                throw new IllegalArgumentException("Evidence selection cannot expose a formal sink");
            }
            if (runtimeMode == RuntimeMode.SHADOW) {
                if (reason != DecisionReason.AUTHENTICATED_SIGNED_SYNTHETIC_SHADOW
                        || javaRoomEpoch < 1
                        || javaFencingToken < 1) {
                    throw new IllegalArgumentException(
                            "SHADOW requires current signed synthetic Java authority");
                }
            } else if (javaRoomEpoch != 0 || javaFencingToken != 0) {
                throw new IllegalArgumentException(
                        "disabled selection cannot carry executable authority");
            }
        }

        private static SelectionDecision disabled(DecisionReason reason) {
            return new SelectionDecision(RuntimeMode.DISABLED, reason, 0, 0, 0, false);
        }

        private static SelectionDecision signedSyntheticShadow(long roomEpoch, long fencingToken) {
            return new SelectionDecision(
                    RuntimeMode.SHADOW,
                    DecisionReason.AUTHENTICATED_SIGNED_SYNTHETIC_SHADOW,
                    roomEpoch,
                    fencingToken,
                    0,
                    false);
        }
    }
}

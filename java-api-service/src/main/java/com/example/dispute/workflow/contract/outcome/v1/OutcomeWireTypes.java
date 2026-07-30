package com.example.dispute.workflow.contract.outcome.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public final class OutcomeWireTypes {

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final long MAX_OPERATION_SEQUENCE = 64L;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern OPAQUE_REF =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");

    private OutcomeWireTypes() {}

    public enum RuntimeMode {
        DISABLED,
        JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW,
        TEMPORAL
    }

    public enum ReviewDecision {
        APPROVE,
        MODIFY_AND_APPROVE,
        REJECT,
        REQUEST_MORE_EVIDENCE,
        ESCALATE_MANUAL
    }

    public enum SlaFactType {
        SYSTEM_SLA_ESCALATION
    }

    public enum ActorType {
        SYSTEM
    }

    public enum EffectClass {
        NO_EXTERNAL_EFFECT,
        REVERSIBLE,
        IRREVERSIBLE
    }

    public enum OperationStatus {
        PLANNED,
        DISPATCHING,
        RECONCILING,
        SUCCEEDED,
        FAILED
    }

    public enum TerminalStatus {
        SUCCEEDED,
        FAILED
    }

    public enum AttemptObservationStatus {
        AMBIGUOUS
    }

    public enum ExternalEffectTruth {
        UNKNOWN
    }

    public enum ReconciliationResolution {
        CONFIRMED_SUCCESS,
        CONFIRMED_FAILURE,
        NOT_FOUND_SAFE_TO_RETRY,
        UNRESOLVED
    }

    public enum CompensationStatus {
        SUCCEEDED,
        FAILED
    }

    public enum EvaluationStatus {
        SUCCEEDED,
        FAILED,
        MANUAL_RECOVERY_PENDING
    }

    public enum ProjectionPhase {
        WAITING_REVIEW,
        DECISION_COMMITTED,
        SLA_ESCALATED,
        EXECUTING,
        RECONCILING,
        COMPENSATING,
        CLOSURE_PENDING,
        CLOSED,
        EVALUATED,
        MANUAL_RECOVERY
    }

    public enum WriterMode {
        LEGACY,
        SHADOW,
        TEMPORAL
    }

    public enum SyntheticNoopMarker {
        JAVA_SIGNED_SYNTHETIC_NOOP_V1
    }

    static String version(String value, String expected) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException("schemaVersion must be " + expected);
        }
        return value;
    }

    static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return value;
    }

    static String opaqueRef(String value, String field) {
        if (value == null
                || value.contains("://")
                || !OPAQUE_REF.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded opaque ref");
        }
        return value;
    }

    static String optionalOpaqueRef(String value, String field) {
        return value == null ? null : opaqueRef(value, field);
    }

    static String sha256(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }

    static String optionalSha256(String value, String field) {
        return value == null ? null : sha256(value, field);
    }

    static String versionPin(String value, String field) {
        return identifier(value, field);
    }

    static <T> T required(T value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }

    static Instant instant(Instant value, String field) {
        return required(value, field);
    }

    static void reviewWindow(Instant reviewOpenedAt, Instant reviewDeadlineAt) {
        instant(reviewOpenedAt, "reviewOpenedAt");
        instant(reviewDeadlineAt, "reviewDeadlineAt");
        epochMilliseconds(reviewOpenedAt, "reviewOpenedAt");
        epochMilliseconds(reviewDeadlineAt, "reviewDeadlineAt");
        if (!reviewOpenedAt.isBefore(reviewDeadlineAt)) {
            throw new IllegalArgumentException("reviewOpenedAt must be before reviewDeadlineAt");
        }
    }

    private static long epochMilliseconds(Instant value, String field) {
        try {
            return value.toEpochMilli();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    field + " must be representable as epoch milliseconds", exception);
        }
    }

    static void coordinates(long epoch, long revision, long fence) {
        if (epoch < 0 || epoch > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("epoch is outside the safe range");
        }
        if (revision < 0 || revision > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("revision is outside the safe range");
        }
        if (fence < 1 || fence > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("fence is outside the safe range");
        }
    }

    static void eventOrder(long sourceRevision, long revision, long committedEventSequence) {
        count(sourceRevision, "sourceRevision");
        count(revision, "revision");
        positive(committedEventSequence, "committedEventSequence");
        long expectedRevision;
        try {
            expectedRevision = Math.addExact(sourceRevision, 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("sourceRevision cannot be incremented", exception);
        }
        if (expectedRevision > MAX_SAFE_INTEGER || revision != expectedRevision) {
            throw new IllegalArgumentException("revision must equal sourceRevision plus one");
        }
    }

    static long operationSequence(long value) {
        if (value < 1 || value > MAX_OPERATION_SEQUENCE) {
            throw new IllegalArgumentException("operationSequence must be between 1 and 64");
        }
        return value;
    }

    static long requiredOperationCount(long value) {
        if (value < 0 || value > MAX_OPERATION_SEQUENCE) {
            throw new IllegalArgumentException("requiredOperationCount must be between 0 and 64");
        }
        return value;
    }

    static long count(long value, String field) {
        if (value < 0 || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(field + " is outside the safe range");
        }
        return value;
    }

    static long positive(long value, String field) {
        if (value < 1 || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(field + " is outside the safe range");
        }
        return value;
    }

    static void paired(String ref, String hash, String refField, String hashField) {
        if ((ref == null) != (hash == null)) {
            throw new IllegalArgumentException(refField + " and " + hashField + " must be paired");
        }
        optionalOpaqueRef(ref, refField);
        optionalSha256(hash, hashField);
    }
}

package com.example.dispute.workflow.recovery.hearing;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic, payload-free replay evidence for the Phase 6 failure matrix. */
public final class HearingReliabilityHarness {

    public static final String SCENARIO_V1 = "hearing-reliability-scenario.v1";
    public static final String REPORT_V1 = "hearing-reliability-report.v1";
    private static final int MIN_REPLAYS = 2;
    private static final int MAX_REPLAYS = 16;

    public ReplayReport verify(Scenario scenario, ScenarioProbe probe) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(probe, "probe must not be null");
        List<String> observations = new ArrayList<>(scenario.replayCount());
        EnumSet<Invariant> common = EnumSet.allOf(Invariant.class);
        boolean safeOutcomes = true;
        for (int replay = 0; replay < scenario.replayCount(); replay++) {
            Observation observation =
                    Objects.requireNonNull(probe.run(scenario), "observation must not be null");
            observations.add(observation.evidenceHash());
            common.retainAll(observation.satisfiedInvariants());
            safeOutcomes &= observation.outcome() == scenario.boundary().expectedOutcome();
        }
        EnumSet<Invariant> missing = EnumSet.copyOf(scenario.boundary().requiredInvariants());
        missing.removeAll(common);
        boolean reproducible = observations.stream().distinct().count() == 1;
        return new ReplayReport(
                REPORT_V1,
                scenario.scenarioId(),
                scenario.failureClassification(),
                observations,
                common,
                missing,
                reproducible,
                safeOutcomes,
                reportHash(scenario, observations, common, safeOutcomes));
    }

    public ReplayReport requirePassing(Scenario scenario, ScenarioProbe probe) {
        ReplayReport report = verify(scenario, probe);
        if (!report.passed()) {
            throw new IllegalStateException("Hearing reliability replay failed");
        }
        return report;
    }

    @FunctionalInterface
    public interface ScenarioProbe {
        Observation run(Scenario scenario);
    }

    public enum FailureClassification {
        PRODUCT,
        FIXTURE,
        INFRA,
        EXTERNAL_GATE
    }

    public enum FaultBoundary {
        WORKFLOW_KILL_BEFORE_CHECKPOINT(
                Outcome.RETRY_OPERATION,
                Invariant.NO_FORMAL_EFFECT,
                Invariant.SAME_OPERATION_KEY),
        GRAPH_KILL_AFTER_CHECKPOINT(
                Outcome.RESUME_CHECKPOINT,
                Invariant.CHECKPOINT_REUSED,
                Invariant.NO_DUPLICATE_MODEL_INVOCATION,
                Invariant.NO_FORMAL_EFFECT),
        JAVA_COMMIT_RESPONSE_LOST(
                Outcome.RESIGNAL_RECEIPT,
                Invariant.COMMITTED_RECEIPT_REUSED,
                Invariant.NO_DUPLICATE_FORMAL_EFFECT,
                Invariant.SAME_OPERATION_KEY),
        STALE_FENCE_DELIVERY(
                Outcome.REJECTED_STALE,
                Invariant.STALE_FENCE_REJECTED,
                Invariant.HIGHER_FENCE_PRESERVED,
                Invariant.NO_FORMAL_EFFECT),
        ENGINEERING_ROLLBACK(
                Outcome.DISABLED,
                Invariant.COHORT_ZERO,
                Invariant.GRAPH_LEASE_FENCED,
                Invariant.JAVA_TRUTH_RETAINED,
                Invariant.COMPARISONS_RETAINED,
                Invariant.LEGACY_WRITER_ACTIVE);

        private final Outcome expectedOutcome;
        private final Set<Invariant> requiredInvariants;

        FaultBoundary(Outcome expectedOutcome, Invariant... invariants) {
            this.expectedOutcome = expectedOutcome;
            this.requiredInvariants = Set.copyOf(List.of(invariants));
        }

        public Outcome expectedOutcome() {
            return expectedOutcome;
        }

        public Set<Invariant> requiredInvariants() {
            return requiredInvariants;
        }
    }

    public enum Invariant {
        NO_FORMAL_EFFECT,
        SAME_OPERATION_KEY,
        CHECKPOINT_REUSED,
        NO_DUPLICATE_MODEL_INVOCATION,
        COMMITTED_RECEIPT_REUSED,
        NO_DUPLICATE_FORMAL_EFFECT,
        STALE_FENCE_REJECTED,
        HIGHER_FENCE_PRESERVED,
        COHORT_ZERO,
        GRAPH_LEASE_FENCED,
        JAVA_TRUTH_RETAINED,
        COMPARISONS_RETAINED,
        LEGACY_WRITER_ACTIVE
    }

    public enum Outcome {
        RETRY_OPERATION,
        RESUME_CHECKPOINT,
        RESIGNAL_RECEIPT,
        REJECTED_STALE,
        DISABLED,
        INVARIANT_VIOLATION
    }

    public enum ReplayStatus {
        PASS,
        FAIL_NON_REPRODUCIBLE,
        FAIL_INVARIANT,
        FAIL_OUTCOME,
        EXTERNAL_GATE_PENDING
    }

    public record Scenario(
            String schemaVersion,
            FaultBoundary boundary,
            FailureClassification failureClassification,
            String candidateCommit,
            String fixtureHash,
            long deterministicSeed,
            int replayCount) {

        public Scenario {
            if (!SCENARIO_V1.equals(schemaVersion)) {
                throw new IllegalArgumentException("schemaVersion must be " + SCENARIO_V1);
            }
            Objects.requireNonNull(boundary, "boundary must not be null");
            Objects.requireNonNull(
                    failureClassification, "failureClassification must not be null");
            if (candidateCommit == null || !candidateCommit.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("candidateCommit must be a lowercase Git SHA");
            }
            requireSha256(fixtureHash, "fixtureHash");
            if (replayCount < MIN_REPLAYS || replayCount > MAX_REPLAYS) {
                throw new IllegalArgumentException("replayCount must be between 2 and 16");
            }
        }

        public String scenarioId() {
            ObjectNode value = JsonNodeFactory.instance.objectNode();
            value.put("schema_version", schemaVersion);
            value.put("boundary", boundary.name());
            value.put("failure_classification", failureClassification.name());
            value.put("candidate_commit", candidateCommit);
            value.put("fixture_hash", fixtureHash);
            value.put("deterministic_seed", deterministicSeed);
            value.put("replay_count", replayCount);
            return ContractJson.sha256Hex(value);
        }
    }

    public record Observation(
            Outcome outcome, Set<Invariant> satisfiedInvariants, String stateHash, String traceHash) {

        public Observation {
            Objects.requireNonNull(outcome, "outcome must not be null");
            satisfiedInvariants = Set.copyOf(
                    Objects.requireNonNull(satisfiedInvariants, "invariants must not be null"));
            requireSha256(stateHash, "stateHash");
            requireSha256(traceHash, "traceHash");
        }

        public String evidenceHash() {
            ObjectNode value = JsonNodeFactory.instance.objectNode();
            value.put("outcome", outcome.name());
            ArrayNode invariants = value.putArray("invariants");
            satisfiedInvariants.stream()
                    .sorted(Comparator.comparing(Enum::name))
                    .forEach(invariant -> invariants.add(invariant.name()));
            value.put("state_hash", stateHash);
            value.put("trace_hash", traceHash);
            return ContractJson.sha256Hex(value);
        }
    }

    public record ReplayReport(
            String schemaVersion,
            String scenarioId,
            FailureClassification failureClassification,
            List<String> observationHashes,
            Set<Invariant> commonInvariants,
            Set<Invariant> missingInvariants,
            boolean reproducible,
            boolean safeOutcomes,
            String reportHash) {

        public ReplayReport {
            if (!REPORT_V1.equals(schemaVersion)) {
                throw new IllegalArgumentException("schemaVersion must be " + REPORT_V1);
            }
            requireSha256(scenarioId, "scenarioId");
            Objects.requireNonNull(
                    failureClassification, "failureClassification must not be null");
            observationHashes = List.copyOf(observationHashes);
            if (observationHashes.size() < MIN_REPLAYS
                    || observationHashes.size() > MAX_REPLAYS) {
                throw new IllegalArgumentException("observation replay count is invalid");
            }
            observationHashes.forEach(hash -> requireSha256(hash, "observationHash"));
            commonInvariants = Set.copyOf(commonInvariants);
            missingInvariants = Set.copyOf(missingInvariants);
            requireSha256(reportHash, "reportHash");
        }

        public ReplayStatus status() {
            if (failureClassification == FailureClassification.EXTERNAL_GATE) {
                return ReplayStatus.EXTERNAL_GATE_PENDING;
            }
            if (!reproducible) {
                return ReplayStatus.FAIL_NON_REPRODUCIBLE;
            }
            if (!missingInvariants.isEmpty()) {
                return ReplayStatus.FAIL_INVARIANT;
            }
            return safeOutcomes ? ReplayStatus.PASS : ReplayStatus.FAIL_OUTCOME;
        }

        public boolean passed() {
            return status() == ReplayStatus.PASS;
        }

        public boolean blocksPromotion() {
            return status() != ReplayStatus.PASS;
        }
    }

    private static String reportHash(
            Scenario scenario,
            List<String> observations,
            Set<Invariant> invariants,
            boolean safeOutcomes) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("scenario_id", scenario.scenarioId());
        ArrayNode observationNode = value.putArray("observation_hashes");
        observations.forEach(observationNode::add);
        ArrayNode invariantNode = value.putArray("common_invariants");
        invariants.stream()
                .sorted(Comparator.comparing(Enum::name))
                .forEach(invariant -> invariantNode.add(invariant.name()));
        value.put("safe_outcomes", safeOutcomes);
        return ContractJson.sha256Hex(value);
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }
}

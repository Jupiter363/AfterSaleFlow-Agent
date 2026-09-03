package com.example.dispute.workflow.shadow.intake;

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

/** Deterministic, text-free crash/race/rollback replay contract for Intake evidence. */
public final class IntakeReliabilityHarness {

    public static final String SCENARIO_V1 = "intake-reliability-scenario.v1";
    public static final String REPORT_V1 = "intake-reliability-report.v1";
    private static final int MIN_REPLAYS = 2;
    private static final int MAX_REPLAYS = 16;

    public ReplayReport verify(Scenario scenario, ScenarioProbe probe) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(probe, "probe must not be null");

        List<String> observationHashes = new ArrayList<>(scenario.replayCount());
        EnumSet<Invariant> commonInvariants = EnumSet.allOf(Invariant.class);
        boolean safeOutcomes = true;
        for (int replay = 0; replay < scenario.replayCount(); replay++) {
            Observation observation =
                    Objects.requireNonNull(probe.run(scenario), "observation must not be null");
            observationHashes.add(observation.evidenceHash());
            commonInvariants.retainAll(observation.satisfiedInvariants());
            safeOutcomes &= observation.outcome().safe()
                    && observation.outcome() == scenario.boundary().expectedOutcome();
        }

        boolean reproducible = observationHashes.stream().distinct().count() == 1;
        Set<Invariant> missingInvariants = EnumSet.copyOf(scenario.boundary().requiredInvariants());
        missingInvariants.removeAll(commonInvariants);
        return new ReplayReport(
                REPORT_V1,
                scenario.scenarioId(),
                scenario.failureClassification(),
                List.copyOf(observationHashes),
                Set.copyOf(commonInvariants),
                Set.copyOf(missingInvariants),
                reproducible,
                safeOutcomes,
                reportHash(scenario, observationHashes, commonInvariants, safeOutcomes));
    }

    public ReplayReport requirePassing(Scenario scenario, ScenarioProbe probe) {
        ReplayReport report = verify(scenario, probe);
        if (!report.passed()) {
            throw new IllegalStateException(
                    "Intake reliability scenario is not reproducible or violates an invariant");
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

    public enum ScenarioKind {
        CRASH,
        RACE,
        ROLLBACK
    }

    public enum ReplayStatus {
        PASS,
        FAIL_NON_REPRODUCIBLE,
        FAIL_INVARIANT,
        FAIL_OUTCOME,
        EXTERNAL_GATE_PENDING
    }

    public enum Invariant {
        CHECKPOINT_REUSED,
        NO_DUPLICATE_MODEL_INVOCATION,
        TERMINAL_RESULT_REUSED,
        COMMITTED_RECEIPT_REUSED,
        NO_DUPLICATE_FORMAL_EFFECT,
        ONE_RACE_WINNER,
        ONE_ACTIVE_WRITER,
        HIGHER_FENCE_SELECTED,
        STALE_FENCE_REJECTED,
        FORMAL_REFS_PRESERVED,
        INITIATOR_EFFECTS_PRESERVED,
        RESPONDENT_ONLY_RESUME,
        FORWARD_ONLY_AFTER_EVIDENCE
    }

    public enum FaultBoundary {
        GRAPH_BEFORE_MODEL(
                ScenarioKind.CRASH,
                Invariant.CHECKPOINT_REUSED,
                Invariant.NO_DUPLICATE_MODEL_INVOCATION),
        GRAPH_AFTER_MODEL(
                ScenarioKind.CRASH,
                Invariant.CHECKPOINT_REUSED,
                Invariant.NO_DUPLICATE_MODEL_INVOCATION),
        GRAPH_BEFORE_TERMINAL_CHECKPOINT(
                ScenarioKind.CRASH,
                Invariant.CHECKPOINT_REUSED,
                Invariant.NO_DUPLICATE_MODEL_INVOCATION),
        GRAPH_AFTER_TERMINAL_CHECKPOINT(
                ScenarioKind.CRASH,
                Invariant.TERMINAL_RESULT_REUSED,
                Invariant.NO_DUPLICATE_MODEL_INVOCATION),
        JAVA_COMMIT_BEFORE_ACTIVITY_COMPLETION(
                ScenarioKind.CRASH,
                Invariant.COMMITTED_RECEIPT_REUSED,
                Invariant.NO_DUPLICATE_FORMAL_EFFECT),
        DUPLICATE_CONFIRMATION(
                ScenarioKind.RACE,
                Invariant.ONE_RACE_WINNER,
                Invariant.NO_DUPLICATE_FORMAL_EFFECT),
        ROLLBACK_PRE_TERMINAL(
                ScenarioKind.ROLLBACK,
                Invariant.ONE_ACTIVE_WRITER,
                Invariant.HIGHER_FENCE_SELECTED,
                Invariant.STALE_FENCE_REJECTED,
                Invariant.FORMAL_REFS_PRESERVED),
        ROLLBACK_AFTER_INITIATOR(
                ScenarioKind.ROLLBACK,
                Invariant.ONE_ACTIVE_WRITER,
                Invariant.HIGHER_FENCE_SELECTED,
                Invariant.STALE_FENCE_REJECTED,
                Invariant.FORMAL_REFS_PRESERVED,
                Invariant.INITIATOR_EFFECTS_PRESERVED,
                Invariant.RESPONDENT_ONLY_RESUME),
        ROLLBACK_AFTER_EVIDENCE(
                ScenarioKind.ROLLBACK,
                Invariant.ONE_ACTIVE_WRITER,
                Invariant.COMMITTED_RECEIPT_REUSED,
                Invariant.FORWARD_ONLY_AFTER_EVIDENCE);

        private final ScenarioKind kind;
        private final Set<Invariant> requiredInvariants;

        FaultBoundary(ScenarioKind kind, Invariant... requiredInvariants) {
            this.kind = kind;
            this.requiredInvariants = Set.copyOf(List.of(requiredInvariants));
        }

        public ScenarioKind kind() {
            return kind;
        }

        public Set<Invariant> requiredInvariants() {
            return requiredInvariants;
        }

        public Outcome expectedOutcome() {
            return switch (this) {
                case DUPLICATE_CONFIRMATION -> Outcome.REJECTED_STALE_WORK;
                case ROLLBACK_AFTER_EVIDENCE -> Outcome.RECONCILED_FORWARD;
                default -> Outcome.RECOVERED;
            };
        }
    }

    public enum Outcome {
        RECOVERED(true),
        REJECTED_STALE_WORK(true),
        RECONCILED_FORWARD(true),
        INVARIANT_VIOLATION(false),
        INFRASTRUCTURE_UNAVAILABLE(false),
        EXTERNAL_GATE_PENDING(false);

        private final boolean safe;

        Outcome(boolean safe) {
            this.safe = safe;
        }

        public boolean safe() {
            return safe;
        }
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
            requireCandidateCommit(candidateCommit);
            requireSha256(fixtureHash, "fixtureHash");
            if (replayCount < MIN_REPLAYS || replayCount > MAX_REPLAYS) {
                throw new IllegalArgumentException("replayCount must be between 2 and 16");
            }
        }

        public String scenarioId() {
            ObjectNode binding = JsonNodeFactory.instance.objectNode();
            binding.put("schema_version", schemaVersion);
            binding.put("kind", boundary.kind().name());
            binding.put("boundary", boundary.name());
            binding.put("failure_classification", failureClassification.name());
            binding.put("candidate_commit", candidateCommit);
            binding.put("fixture_hash", fixtureHash);
            binding.put("deterministic_seed", deterministicSeed);
            binding.put("replay_count", replayCount);
            return ContractJson.sha256Hex(binding);
        }
    }

    /** A probe may expose only bounded classifications, invariant enums, and digests. */
    public record Observation(
            Outcome outcome, Set<Invariant> satisfiedInvariants, String stateHash, String traceHash) {

        public Observation {
            Objects.requireNonNull(outcome, "outcome must not be null");
            satisfiedInvariants = Set.copyOf(Objects.requireNonNull(
                    satisfiedInvariants, "satisfiedInvariants must not be null"));
            requireSha256(stateHash, "stateHash");
            requireSha256(traceHash, "traceHash");
        }

        public String evidenceHash() {
            ObjectNode evidence = JsonNodeFactory.instance.objectNode();
            evidence.put("outcome", outcome.name());
            ArrayNode invariants = evidence.putArray("satisfied_invariants");
            satisfiedInvariants.stream()
                    .sorted(Comparator.comparing(Enum::name))
                    .forEach(invariant -> invariants.add(invariant.name()));
            evidence.put("state_hash", stateHash);
            evidence.put("trace_hash", traceHash);
            return ContractJson.sha256Hex(evidence);
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
            observationHashes = List.copyOf(
                    Objects.requireNonNull(observationHashes, "observationHashes must not be null"));
            if (observationHashes.size() < MIN_REPLAYS
                    || observationHashes.size() > MAX_REPLAYS) {
                throw new IllegalArgumentException(
                        "observationHashes must contain between 2 and 16 replays");
            }
            observationHashes.forEach(hash -> requireSha256(hash, "observationHash"));
            commonInvariants = Set.copyOf(
                    Objects.requireNonNull(commonInvariants, "commonInvariants must not be null"));
            missingInvariants = Set.copyOf(
                    Objects.requireNonNull(missingInvariants, "missingInvariants must not be null"));
            requireSha256(reportHash, "reportHash");
        }

        public boolean passed() {
            return status() == ReplayStatus.PASS;
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

        public boolean blocksEngineeringCheckpoint() {
            return status() != ReplayStatus.PASS
                    && status() != ReplayStatus.EXTERNAL_GATE_PENDING;
        }

        public boolean blocksPromotion() {
            return status() != ReplayStatus.PASS;
        }
    }

    private static String reportHash(
            Scenario scenario,
            List<String> observationHashes,
            Set<Invariant> commonInvariants,
            boolean safeOutcomes) {
        ObjectNode report = JsonNodeFactory.instance.objectNode();
        report.put("scenario_id", scenario.scenarioId());
        report.put("failure_classification", scenario.failureClassification().name());
        ArrayNode observations = report.putArray("observation_hashes");
        observationHashes.forEach(observations::add);
        ArrayNode invariants = report.putArray("common_invariants");
        commonInvariants.stream()
                .sorted(Comparator.comparing(Enum::name))
                .forEach(invariant -> invariants.add(invariant.name()));
        report.put("safe_outcomes", safeOutcomes);
        return ContractJson.sha256Hex(report);
    }

    private static void requireCandidateCommit(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                    "candidateCommit must be a lowercase 40-character Git SHA");
        }
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }
}

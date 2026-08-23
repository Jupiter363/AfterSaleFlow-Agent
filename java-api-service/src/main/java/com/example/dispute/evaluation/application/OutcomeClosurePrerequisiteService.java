package com.example.dispute.evaluation.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure fail-closed ordering guard for future authoritative operation and compensation receipts. */
public final class OutcomeClosurePrerequisiteService {

    public Decision assess(Request request) {
        List<String> blockers = new ArrayList<>();
        Map<String, Observation> byId = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (Observation observation : request.observations()) {
            if (byId.putIfAbsent(observation.operationId(), observation) != null) {
                duplicates.add(observation.operationId());
            }
            if (observation.status() == Status.AMBIGUOUS) {
                blockers.add("UNRESOLVED_AMBIGUOUS:" + observation.operationId());
            }
        }
        duplicates.stream().sorted().forEach(id -> blockers.add("DUPLICATE_OBSERVATION:" + id));
        assessRequired(
                Kind.OPERATION,
                request.requiredOperationRequestHashes(),
                request,
                byId,
                blockers);
        assessRequired(
                Kind.COMPENSATION,
                request.requiredCompensationRequestHashes(),
                request,
                byId,
                blockers);
        return new Decision(blockers.isEmpty(), blockers);
    }

    public void requireReady(Request request) {
        Decision decision = assess(request);
        if (!decision.ready()) {
            throw new IllegalStateException(
                    "outcome closure prerequisites are not satisfied: " + decision.blockers());
        }
    }

    private static void assessRequired(
            Kind kind,
            Map<String, String> required,
            Request request,
            Map<String, Observation> byId,
            List<String> blockers) {
        required.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry -> {
                            String id = entry.getKey();
                            Observation observation = byId.get(id);
                            if (observation == null) {
                                blockers.add("MISSING_" + kind + ":" + id);
                                return;
                            }
                            if (observation.kind() != kind) {
                                blockers.add("KIND_MISMATCH:" + id);
                            }
                            if (!observation.javaAuthoritative()) {
                                blockers.add("NON_AUTHORITATIVE:" + id);
                            }
                            if (observation.epoch() != request.epoch()
                                    || observation.revision() != request.revision()
                                    || observation.fence() != request.fence()) {
                                blockers.add("STALE_REVISION_OR_FENCE:" + id);
                            }
                            if (!entry.getValue().equals(observation.requestHash())) {
                                blockers.add("REQUEST_HASH_MISMATCH:" + id);
                            }
                            if (observation.status() != Status.SUCCEEDED) {
                                blockers.add("NOT_SUCCEEDED:" + id);
                            }
                        });
    }

    public enum Kind {
        OPERATION,
        COMPENSATION
    }

    public enum Status {
        PENDING,
        SUCCEEDED,
        FAILED,
        AMBIGUOUS,
        MANUAL_RECOVERY
    }

    public record Observation(
            String operationId,
            Kind kind,
            Status status,
            boolean javaAuthoritative,
            long epoch,
            long revision,
            long fence,
            String requestHash,
            String receiptHash) {
        public Observation {
            if (operationId == null
                    || !operationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    || kind == null
                    || status == null
                    || epoch < 0
                    || revision < 0
                    || fence < 1
                    || requestHash == null
                    || !requestHash.matches("[0-9a-f]{64}")
                    || (receiptHash != null && !receiptHash.matches("[0-9a-f]{64}"))
                    || (status == Status.SUCCEEDED && receiptHash == null)) {
                throw new IllegalArgumentException("invalid closure observation");
            }
        }
    }

    public record Request(
            long epoch,
            long revision,
            long fence,
            Map<String, String> requiredOperationRequestHashes,
            Map<String, String> requiredCompensationRequestHashes,
            List<Observation> observations) {
        public Request {
            if (epoch < 0 || revision < 0 || fence < 1) {
                throw new IllegalArgumentException("invalid closure revision or fence");
            }
            requiredOperationRequestHashes = copyHashes(requiredOperationRequestHashes);
            requiredCompensationRequestHashes = copyHashes(requiredCompensationRequestHashes);
            observations = List.copyOf(observations);
        }

        private static Map<String, String> copyHashes(Map<String, String> values) {
            Map<String, String> copy = Map.copyOf(values);
            copy.forEach(
                    (id, hash) -> {
                        if (id == null
                                || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                                || hash == null
                                || !hash.matches("[0-9a-f]{64}")) {
                            throw new IllegalArgumentException("invalid required operation binding");
                        }
                    });
            return copy;
        }
    }

    public record Decision(boolean ready, List<String> blockers) {
        public Decision {
            blockers = List.copyOf(blockers);
            if (ready != blockers.isEmpty()) {
                throw new IllegalArgumentException("ready must match blocker state");
            }
        }
    }
}

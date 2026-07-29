package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort.ActivationGrant;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/** Validates the three frozen hash preimages and both target-lane envelopes. */
public final class TargetE2eFinalizationBindingVerifier {

    private static final String LANE = TargetE2eExecutionLaneVerifier.EXECUTION_LANE;
    private static final String INTAKE_ARTIFACT_ID_PREFIX = "intake.proposal.";
    private static final String TARGET_PROPOSAL_ID_PREFIX = "target-proposal.";
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Set<String> COMMAND_ENVELOPE_FIELDS = Set.of(
            "schema_version", "execution_lane", "activation_id", "room_fencing_token",
            "command_hash", "command_envelope_hash", "command");
    private static final Set<String> RESULT_ENVELOPE_FIELDS = Set.of(
            "schema_version", "execution_lane", "activation_id", "room_fencing_token",
            "command_hash", "command_envelope_hash", "result_hash", "proposal_hash",
            "result_envelope_hash", "graph_output_authority", "result");
    private static final Set<String> DB_BINDING_FIELDS = Set.of(
            "schema_version", "environment_id", "environment_generation", "activation_id",
            "binding_kind", "cluster_identity", "database_identity",
            "runtime_principal_identity", "binding_hash");
    private static final Set<String> PROPOSAL_SOURCE_FIELDS =
            Set.of("schema_version", "room_type", "proposal");
    private static final Set<String> PROPOSAL_FIELDS = Set.of(
            "schema_version", "proposal_id", "command_id", "logical_run_id", "attempt_id",
            "payload_schema_version", "payload_ref", "payload_hash", "terminal_class",
            "formal_authority");

    private final ObjectMapper objectMapper;

    public TargetE2eFinalizationBindingVerifier(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public VerifiedEvidence verify(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            TargetE2eIntakeFinalizationState state,
            TargetE2eFinalizationEvidence evidence) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(evidence, "evidence");
        JsonNode commandEnvelope = evidence.commandEnvelope();
        exactFields(commandEnvelope, COMMAND_ENVELOPE_FIELDS, "command envelope");
        text(commandEnvelope, "schema_version", "target-e2e-graph-command-envelope.v1");
        text(commandEnvelope, "execution_lane", LANE);
        long fence = positiveLong(commandEnvelope, "room_fencing_token");
        requireEqual(fence, state.run().fencingToken(), "command room fence");
        String activationId = activationId(commandEnvelope);
        requireEqual(
                request.command().requestHash(),
                IntakeContractHashes.graphCommandHash(request.command()),
                "embedded command request hash");
        String commandHash = ContractJson.sha256Hex(objectMapper.valueToTree(request.command()));
        text(commandEnvelope, "command_hash", commandHash);
        requireCanonicalJsonEqual(
                commandEnvelope.required("command"),
                objectMapper.valueToTree(request.command()),
                "embedded graph command");
        String commandEnvelopeHash = selfHash(commandEnvelope, "command_envelope_hash");

        JsonNode proposalSource = evidence.proposalSource();
        exactFields(proposalSource, PROPOSAL_SOURCE_FIELDS, "proposal source");
        text(proposalSource, "schema_version", "target-e2e-room-proposal-source.v1");
        text(proposalSource, "room_type", "INTAKE");
        JsonNode proposal = proposalSource.required("proposal");
        exactFields(proposal, PROPOSAL_FIELDS, "proposal");
        text(proposal, "schema_version", "target-e2e-intake-proposal.v1");
        text(proposal, "command_id", request.command().commandId());
        text(proposal, "logical_run_id", request.logicalRunId());
        text(proposal, "attempt_id", request.attemptId());
        text(proposal, "payload_schema_version", "intake-turn-proposal.v2");
        text(proposal, "terminal_class", "COMPLETED");
        boundedIdentifier(proposal, "proposal_id");
        boundedIdentifier(proposal, "command_id");
        boundedIdentifier(proposal, "logical_run_id");
        boundedIdentifier(proposal, "attempt_id");
        boundedIdentifier(proposal, "payload_schema_version");
        String payloadRef = text(proposal, "payload_ref");
        if (payloadRef.length() > 512 || !payloadRef.startsWith("urn:target-e2e:proposal:")) {
            throw rejected("TARGET_E2E_SOURCE_SCHEMA_INVALID", "payload_ref is invalid");
        }
        String payloadHash = sha256(proposal, "payload_hash");
        JsonNode formalAuthority = proposal.required("formal_authority");
        if (!formalAuthority.isBoolean() || formalAuthority.booleanValue()) {
            throw rejected("TARGET_E2E_GRAPH_AUTHORITY_INVALID", "proposal grants formal authority");
        }
        var artifact = proposalArtifact(result);
        requireEqual(artifact.sha256(), payloadHash, "proposal artifact sha256");
        String hashPrefix = artifact.sha256().substring(0, 32);
        text(proposal, "proposal_id", TARGET_PROPOSAL_ID_PREFIX + hashPrefix);
        requireEqual(
                artifact.artifactId(),
                INTAKE_ARTIFACT_ID_PREFIX + hashPrefix,
                "proposal artifact_id");
        requireEqual(
                artifact.schemaVersion(),
                text(proposal, "payload_schema_version"),
                "proposal artifact schema_version");
        requireEqual(
                payloadRef,
                "urn:target-e2e:proposal:intake:" + artifact.sha256(),
                "proposal source payload_ref");
        String proposalHash = ContractJson.sha256Hex(proposal);

        JsonNode resultEnvelope = evidence.resultEnvelope();
        exactFields(resultEnvelope, RESULT_ENVELOPE_FIELDS, "result envelope");
        text(resultEnvelope, "schema_version", "target-e2e-graph-result-envelope.v1");
        text(resultEnvelope, "execution_lane", LANE);
        text(resultEnvelope, "activation_id", activationId);
        requireEqual(
                positiveLong(resultEnvelope, "room_fencing_token"),
                fence,
                "result room fence");
        text(resultEnvelope, "command_hash", commandHash);
        text(resultEnvelope, "command_envelope_hash", commandEnvelopeHash);
        requireEqual(
                result.resultHash(),
                IntakeContractHashes.graphResultHash(result.graphResult()),
                "nested graph result output hash");
        text(resultEnvelope, "result_hash", result.resultHash());
        text(resultEnvelope, "proposal_hash", proposalHash);
        text(resultEnvelope, "graph_output_authority", "PROPOSAL_ONLY");
        requireCanonicalJsonEqual(
                resultEnvelope.required("result"),
                objectMapper.valueToTree(result.graphResult()),
                "embedded graph result");
        String resultEnvelopeHash = selfHash(resultEnvelope, "result_envelope_hash");

        JsonNode dbBinding = evidence.isolatedDomainDbBinding();
        exactFields(dbBinding, DB_BINDING_FIELDS, "isolated Domain DB binding");
        text(dbBinding, "schema_version", "target-e2e-isolated-domain-db-binding.v1");
        text(dbBinding, "activation_id", activationId);
        text(dbBinding, "binding_kind", "ISOLATED_DOMAIN_POSTGRESQL");
        positiveLong(dbBinding, "environment_generation");
        boundedIdentifier(dbBinding, "environment_id");
        boundedIdentifier(dbBinding, "cluster_identity");
        boundedIdentifier(dbBinding, "database_identity");
        boundedIdentifier(dbBinding, "runtime_principal_identity");
        String dbBindingHash = selfHash(dbBinding, "binding_hash");
        return new VerifiedEvidence(
                activationId,
                evidence.activationManifestHash(),
                commandHash,
                commandEnvelopeHash,
                result.resultHash(),
                proposalHash,
                resultEnvelopeHash,
                dbBindingHash);
    }

    public void requireGrantBindings(ActivationGrant grant, VerifiedEvidence evidence) {
        requireEqual(grant.activationId(), evidence.activationId(), "activation id");
        requireEqual(
                grant.activationManifestHash(),
                evidence.activationManifestHash(),
                "activation manifest hash");
        requireEqual(
                grant.isolatedDomainDbBindingHash(),
                evidence.isolatedDomainDbBindingHash(),
                "isolated Domain DB binding hash");
    }

    private static com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer
            proposalArtifact(ExecuteAgentRunResult result) {
        if (result.graphResult() == null
                || result.graphResult().artifactOperations().size() != 1
                || result.graphResult().artifactOperations().getFirst().operation()
                        != ArtifactOperationType.PROPOSE_PATCH) {
            throw rejected(
                    "TARGET_E2E_PROPOSAL_REFERENCE_MISSING",
                    "result must carry exactly one proposal artifact");
        }
        return result.graphResult().artifactOperations().getFirst().artifact();
    }

    private static String selfHash(JsonNode document, String field) {
        String expected = sha256(document, field);
        ObjectNode preimage = document.deepCopy();
        preimage.remove(field);
        String actual = ContractJson.sha256Hex(preimage);
        requireEqual(expected, actual, field);
        return actual;
    }

    private static String activationId(JsonNode document) {
        String value = text(document, "activation_id");
        if (!value.matches("p9act[.]v1[.][0-9a-f]{32}")) {
            throw rejected("TARGET_E2E_ACTIVATION_ID_INVALID", "activation id is invalid");
        }
        return value;
    }

    private static String boundedIdentifier(JsonNode document, String field) {
        String value = text(document, field);
        if (value.length() > 128 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
            throw rejected("TARGET_E2E_SOURCE_SCHEMA_INVALID", field + " is invalid");
        }
        return value;
    }

    private static String sha256(JsonNode document, String field) {
        String value = text(document, field);
        if (!value.matches("[0-9a-f]{64}")) {
            throw rejected("TARGET_E2E_SOURCE_SCHEMA_INVALID", field + " is not a SHA-256");
        }
        return value;
    }

    private static String text(JsonNode document, String field) {
        JsonNode value = document.required(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw rejected("TARGET_E2E_SOURCE_SCHEMA_INVALID", field + " is not text");
        }
        return value.textValue();
    }

    private static void text(JsonNode document, String field, String expected) {
        requireEqual(text(document, field), expected, field);
    }

    private static long positiveLong(JsonNode document, String field) {
        JsonNode value = document.required(field);
        if (!value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < 1
                || value.longValue() > MAX_SAFE_INTEGER) {
            throw rejected("TARGET_E2E_SOURCE_SCHEMA_INVALID", field + " is invalid");
        }
        return value.longValue();
    }

    private static void exactFields(JsonNode document, Set<String> expected, String name) {
        if (!document.isObject()) {
            throw rejected("TARGET_E2E_SOURCE_SCHEMA_INVALID", name + " is not an object");
        }
        Set<String> actual = new HashSet<>();
        Iterator<String> fields = document.fieldNames();
        fields.forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw rejected(
                    "TARGET_E2E_SOURCE_SCHEMA_INVALID", name + " fields are not exact");
        }
    }

    private static void requireEqual(Object actual, Object expected, String field) {
        if (!Objects.equals(actual, expected)) {
            throw rejected(
                    "TARGET_E2E_BINDING_MISMATCH", field + " conflicts with its hash source");
        }
    }

    private static void requireCanonicalJsonEqual(
            JsonNode actual, JsonNode expected, String field) {
        requireEqual(ContractJson.canonicalString(actual), ContractJson.canonicalString(expected), field);
    }

    private static TargetE2eFinalizationRejectedException rejected(
            String code, String message) {
        return new TargetE2eFinalizationRejectedException(code, message);
    }

    public record VerifiedEvidence(
            String activationId,
            String activationManifestHash,
            String commandHash,
            String commandEnvelopeHash,
            String resultHash,
            String proposalHash,
            String resultEnvelopeHash,
            String isolatedDomainDbBindingHash) {}
}

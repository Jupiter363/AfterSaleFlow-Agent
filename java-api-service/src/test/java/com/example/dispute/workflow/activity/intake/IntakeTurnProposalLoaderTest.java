package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeImmutableProposalReader;
import com.example.dispute.workflow.application.intake.IntakeProposalAuthority;
import com.example.dispute.workflow.application.intake.IntakeProposalLoadException;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal;
import com.example.dispute.workflow.application.intake.IntakeTurnProposalLoader;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IntakeTurnProposalLoaderTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path CONTRACT_ROOT = Path.of(
            "..", "contracts", "agent-platform", "intake", "v2");
    private static final Path PROPOSAL_FIXTURE =
            CONTRACT_ROOT.resolve("fixtures/valid/intake-turn-proposal-valid.json");

    @Test
    void reloadsCanonicalProposalAndVerifiesEveryAuthorityBinding() throws Exception {
        JsonNode fixture = fixture();
        byte[] payload = ContractJson.canonicalize(fixture);
        IntakeProposalReference reference = reference(fixture, payload);
        var reader = new ExactReader(reference, payload);

        var loaded = new IntakeTurnProposalLoader(reader).load(reference, authority(fixture));

        assertThat(loaded.proposal().proposalHash()).isEqualTo(reference.sha256());
        assertThat(loaded.proposal().agentSessionId())
                .isEqualTo("AGENT_SESSION_P4_USER_1");
        assertThat(loaded.proposal().profileVersions().toolPolicyVersion())
                .isEqualTo("no-tools.v1");
        assertThat(reader.calls).isOne();
    }

    @Test
    void rejectsObjectVersionDriftBeforeParsingThePayload() throws Exception {
        JsonNode fixture = fixture();
        byte[] payload = ContractJson.canonicalize(fixture);
        IntakeProposalReference reference = reference(fixture, payload);
        IntakeImmutableProposalReader reader = ignored ->
                new IntakeImmutableProposalReader.StoredProposal(
                        reference.artifactId(),
                        reference.schemaVersion(),
                        reference.uri(),
                        "version-2",
                        reference.sha256(),
                        reference.sizeBytes(),
                        payload);

        assertRejected(
                "INTAKE_PROPOSAL_REFERENCE_MISMATCH",
                () -> new IntakeTurnProposalLoader(reader).load(reference, authority(fixture)));
    }

    @Test
    void preservesTransientObjectStoreFailuresAsRetryableAccessErrors() throws Exception {
        JsonNode fixture = fixture();
        byte[] payload = ContractJson.canonicalize(fixture);
        IntakeProposalReference reference = reference(fixture, payload);
        IntakeProposalLoadException transientFailure = new IntakeProposalLoadException(
                "proposal object store is temporarily unavailable",
                new IllegalStateException("object store timeout"));
        IntakeImmutableProposalReader reader = ignored -> {
            throw transientFailure;
        };

        assertThatThrownBy(() -> new IntakeTurnProposalLoader(reader)
                        .load(reference, authority(fixture)))
                .isSameAs(transientFailure);
    }

    @Test
    void rejectsAnUnclassifiedReaderFailureWithoutRetryingIt() throws Exception {
        JsonNode fixture = fixture();
        byte[] payload = ContractJson.canonicalize(fixture);
        IntakeProposalReference reference = reference(fixture, payload);
        IntakeImmutableProposalReader reader = ignored -> {
            throw new IllegalStateException("unclassified SDK failure");
        };

        assertRejected(
                "INTAKE_PROPOSAL_READER_UNEXPECTED",
                () -> new IntakeTurnProposalLoader(reader).load(reference, authority(fixture)));
    }

    @Test
    void rejectsNonCanonicalBytesEvenWhenTheSelfHashIsValid() throws Exception {
        JsonNode fixture = fixture();
        byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(fixture);
        IntakeProposalReference reference = reference(fixture, pretty);
        var reader = new ExactReader(reference, pretty);

        assertRejected(
                "INTAKE_PROPOSAL_NOT_CANONICAL",
                () -> new IntakeTurnProposalLoader(reader).load(reference, authority(fixture)));
    }

    @Test
    void rejectsHashDriftAndCrossSessionAuthority() throws Exception {
        ObjectNode changed = (ObjectNode) fixture();
        changed.put("room_utterance", "Changed after Graph committed the pointer.");
        byte[] payload = ContractJson.canonicalize(changed);
        IntakeProposalReference reference = reference(changed, payload);
        var reader = new ExactReader(reference, payload);

        assertRejected(
                "INTAKE_PROPOSAL_HASH_MISMATCH",
                () -> new IntakeTurnProposalLoader(reader).load(reference, authority(changed)));

        ObjectNode valid = (ObjectNode) fixture();
        byte[] validPayload = ContractJson.canonicalize(valid);
        IntakeProposalReference validReference = reference(valid, validPayload);
        IntakeProposalAuthority expected = authority(valid);
        IntakeProposalAuthority crossSession = new IntakeProposalAuthority(
                expected.commandId(),
                expected.logicalRunId(),
                expected.attemptId(),
                expected.caseId(),
                expected.roomEpoch(),
                expected.threadId(),
                expected.actorScopeHash(),
                "AGENT_SESSION_P4_USER_OTHER",
                expected.cognitiveRevision(),
                expected.sourceSnapshotHash(),
                expected.sourceEventHash(),
                expected.profileVersions());

        assertRejected(
                "INTAKE_PROPOSAL_AUTHORITY_MISMATCH",
                () -> new IntakeTurnProposalLoader(
                                new ExactReader(validReference, validPayload))
                        .load(validReference, crossSession));
    }

    @Test
    void rejectsUnknownAndFormalActionFieldsThroughTheFrozenSchema() throws Exception {
        ObjectNode proposal = (ObjectNode) fixture();
        proposal.put("unknown_top_level", true);
        ObjectNode caseStory =
                (ObjectNode) proposal.required("dossier_patch").required("case_story");
        caseStory.put("open_evidence", true);
        proposal.put(
                "proposal_hash",
                IntakeContractHashes.canonicalHashExcluding(proposal, "proposal_hash"));
        byte[] payload = ContractJson.canonicalize(proposal);
        IntakeProposalReference reference = reference(proposal, payload);

        assertRejected(
                "INTAKE_PROPOSAL_SCHEMA_INVALID",
                () -> new IntakeTurnProposalLoader(new ExactReader(reference, payload))
                        .load(reference, authority(proposal)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "credentials",
        "credential",
        "password",
        "api_key",
        "access_token",
        "refresh_token",
        "authorization_header",
        "private_key",
        "client_secret",
        "raw_audit_records",
        "audit_records",
        "reviewer_notes",
        "other_party_private_messages",
        "opposing_party_private_messages",
        "private_conversation",
        "internal_notes",
        "opposing_party_messages",
        "opposing_party_private",
        "other_party_messages",
        "other_party_private",
        "trusted_model_profile",
        "prompt_version",
        "model_profile_id",
        "policy_version",
        "guardrail_version",
        "tool_policy_version",
        "process_state",
        "case_status",
        "room_transition",
        "evidence_deadline",
        "review_instructions",
        "tool_instructions",
        "admit_case",
        "cancel_case",
        "cancel_intake",
        "freeze_matrix",
        "open_room",
        "set_deadline",
        "invite_participant"
    })
    void recursivelyRejectsEverySensitiveAndFormalActionKey(String forbiddenKey)
            throws Exception {
        ObjectNode proposal = (ObjectNode) fixture();
        ObjectNode caseStory =
                (ObjectNode) proposal.required("dossier_patch").required("case_story");
        caseStory.putObject("nested").put(forbiddenKey, "blocked");
        proposal.put(
                "proposal_hash",
                IntakeContractHashes.canonicalHashExcluding(proposal, "proposal_hash"));
        byte[] payload = ContractJson.canonicalize(proposal);
        IntakeProposalReference reference = reference(proposal, payload);

        assertRejected(
                "INTAKE_PROPOSAL_SCHEMA_INVALID",
                () -> new IntakeTurnProposalLoader(new ExactReader(reference, payload))
                        .load(reference, authority(proposal)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"case_fact_matrix", "unilateral_case_matrix"})
    void matrixChangesCannotBypassTheDedicatedMatrixPatch(String matrixBranch)
            throws Exception {
        ObjectNode proposal = (ObjectNode) fixture();
        ((ObjectNode) proposal.required("dossier_patch"))
                .set(matrixBranch, MAPPER.createObjectNode().put("schema_version", "untrusted"));
        proposal.put(
                "proposal_hash",
                IntakeContractHashes.canonicalHashExcluding(proposal, "proposal_hash"));
        byte[] payload = ContractJson.canonicalize(proposal);
        IntakeProposalReference reference = reference(proposal, payload);

        assertRejected(
                "INTAKE_PROPOSAL_SCHEMA_INVALID",
                () -> new IntakeTurnProposalLoader(new ExactReader(reference, payload))
                        .load(reference, authority(proposal)));
    }

    @Test
    void acceptsOnlyTheFrozenUnilateralSemanticDraftShapeForMatrixPatch() throws Exception {
        ObjectNode proposal = (ObjectNode) fixture();
        ObjectNode draft = MAPPER.createObjectNode();
        draft.put("schema_version", "unilateral_case_matrix.draft.v1");
        ObjectNode row = draft.putArray("fact_rows").addObject();
        row.put("fact_key", "NEW_INSTALL_SCOPE");
        row.put("category", "PRODUCT_PAGE");
        row.put("fact_target", "The listing included installation.");
        row.put("materiality", "CORE");
        row.put("position_summary", "The buyer relied on the listing.");
        row.put("asserted_value", "Installation included");
        row.put("source_scope", "CURRENT_SOURCE");
        draft.putArray("summary_source_fact_keys").add("NEW_INSTALL_SCOPE");
        proposal.set("matrix_patch", draft);
        proposal.put(
                "proposal_hash",
                IntakeContractHashes.canonicalHashExcluding(proposal, "proposal_hash"));
        byte[] payload = ContractJson.canonicalize(proposal);
        IntakeProposalReference reference = reference(proposal, payload);

        IntakeTurnProposal loaded = new IntakeTurnProposalLoader(
                        new ExactReader(reference, payload))
                .load(reference, authority(proposal))
                .proposal();

        assertThat(loaded.matrixPatch().path("schema_version").asText())
                .isEqualTo("unilateral_case_matrix.draft.v1");
    }

    @Test
    void embeddedSchemaIsCanonicallyIdenticalToTheFrozenPublicContract() throws Exception {
        Path embedded = Path.of(
                "src",
                "main",
                "resources",
                "contracts",
                "agent-platform",
                "intake",
                "v2",
                "intake-turn-proposal.schema.json");

        JsonNode publicSchema =
                MAPPER.readTree(CONTRACT_ROOT.resolve("intake-turn-proposal.schema.json").toFile());
        JsonNode embeddedSchema = MAPPER.readTree(embedded.toFile());
        assertThat(ContractJson.canonicalize(embeddedSchema))
                .containsExactly(ContractJson.canonicalize(publicSchema));
    }

    private static JsonNode fixture() throws Exception {
        return MAPPER.readTree(PROPOSAL_FIXTURE.toFile());
    }

    private static IntakeProposalReference reference(JsonNode proposal, byte[] payload) {
        return new IntakeProposalReference(
                "PROPOSAL_P4_USER_2",
                "intake-turn-proposal.v2",
                "urn:intake:proposal:PROPOSAL_P4_USER_2",
                "version-1",
                proposal.required("proposal_hash").asText(),
                payload.length);
    }

    private static IntakeProposalAuthority authority(JsonNode proposal) {
        JsonNode profiles = proposal.required("profile_versions");
        return new IntakeProposalAuthority(
                proposal.required("command_id").asText(),
                proposal.required("logical_run_id").asText(),
                proposal.required("attempt_id").asText(),
                proposal.required("case_id").asText(),
                proposal.required("room_epoch").longValue(),
                proposal.required("thread_id").asText(),
                proposal.required("actor_scope_hash").asText(),
                proposal.required("agent_session_id").asText(),
                proposal.required("cognitive_revision").longValue(),
                proposal.required("source_snapshot_hash").asText(),
                proposal.path("source_event_hash").isMissingNode()
                        ? null
                        : proposal.required("source_event_hash").asText(),
                new IntakeTurnProposal.ProfileVersions(
                        profiles.required("graph_version").asText(),
                        profiles.required("checkpoint_schema_version").asText(),
                        profiles.required("prompt_version").asText(),
                        profiles.required("model_profile_id").asText(),
                        profiles.required("output_schema_version").asText(),
                        profiles.required("policy_version").asText(),
                        profiles.required("guardrail_version").asText(),
                        profiles.required("tool_policy_version").asText()));
    }

    private static void assertRejected(String code, ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .satisfies(
                        failure ->
                                assertThat(((IntakeFinalizationRejectedException) failure).code())
                                        .isEqualTo(code));
    }

    private static final class ExactReader implements IntakeImmutableProposalReader {
        private final IntakeProposalReference reference;
        private final byte[] payload;
        private int calls;

        private ExactReader(IntakeProposalReference reference, byte[] payload) {
            this.reference = reference;
            this.payload = payload.clone();
        }

        @Override
        public StoredProposal load(IntakeProposalReference ignored) {
            calls++;
            return new StoredProposal(
                    reference.artifactId(),
                    reference.schemaVersion(),
                    reference.uri(),
                    reference.objectVersion(),
                    reference.sha256(),
                    reference.sizeBytes(),
                    payload);
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}

package com.example.dispute.workflow.authority.payload;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority.Party;
import com.example.dispute.workflow.application.authority.payload.IntakeAuthorityRoute;
import com.example.dispute.workflow.application.authority.payload.IntakeCommandAuthority;
import com.example.dispute.workflow.application.authority.payload.IntakeHumanInputCommand;
import com.example.dispute.workflow.application.authority.payload.IntakePayloadAuthority;
import com.example.dispute.workflow.application.authority.payload.IntakePayloadPutReceipt;
import com.example.dispute.workflow.application.authority.payload.IntakePayloadSourceKind;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class IntakePayloadAuthorityModelTest {

    private static final String HASH = "a".repeat(64);
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 22, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void rejectsPutReceiptFromAnotherSourceKind() {
        IntakePayloadPutReceipt branchReceipt = receipt(
                IntakePayloadSourceKind.SERVER_CANONICAL_BRANCH,
                IntakePayloadSourceKind.SERVER_CANONICAL_BRANCH.schemaVersion());

        assertThatThrownBy(() -> new IntakePayloadAuthority(
                        "PAYLOAD-1",
                        "COMMAND-1",
                        route(),
                        IntakePayloadSourceKind.SERVER_MINTED_HUMAN_INPUT,
                        null,
                        "ARTIFACT-1",
                        IntakePayloadSourceKind.SERVER_MINTED_HUMAN_INPUT.schemaVersion(),
                        "urn:payload:1",
                        "VERSION-1",
                        HASH,
                        1,
                        branchReceipt,
                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source kind");
    }

    @Test
    void blocksServerMintedPayloadsFromTheInertDisposition() {
        IntakePayloadAuthority payload = humanInputPayload();

        assertThatThrownBy(() -> command(
                        CommandType.INTAKE_MESSAGE,
                        IntakeCommandAuthority.ExecutionDisposition.INERT_EXTERNAL_EVENT)
                .requirePayload(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution disposition");

        assertThatThrownBy(() -> command(
                        CommandType.INTAKE_MESSAGE,
                        IntakeCommandAuthority.ExecutionDisposition.ACTIVITY_ORCHESTRATED)
                .requirePayload(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current authority gate");
    }

    @Test
    void rejectsHumanInputEpochOutsideTheJcsSafeIntegerRange() {
        assertThatThrownBy(() -> new IntakeHumanInputCommand(
                        IntakeHumanInputCommand.SCHEMA_VERSION,
                        "COMMAND-1",
                        "TENANT-1",
                        "CASE-1",
                        9_007_199_254_740_992L,
                        Party.INITIATOR,
                        "ACTOR-1",
                        ActorRole.USER,
                        "ACCESS-1",
                        "REG-1",
                        "MESSAGE-1",
                        "hello",
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JCS-safe");
    }

    @Test
    void rejectsMissingRouteInsteadOfDereferencingIt() {
        IntakeHumanInputCommand command = new IntakeHumanInputCommand(
                IntakeHumanInputCommand.SCHEMA_VERSION,
                "COMMAND-1",
                "TENANT-1",
                "CASE-1",
                0,
                Party.INITIATOR,
                "ACTOR-1",
                ActorRole.USER,
                "ACCESS-1",
                "REG-1",
                "MESSAGE-1",
                "hello",
                0);

        assertThatThrownBy(() -> command.requireRoute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("route");
    }

    private static IntakePayloadAuthority humanInputPayload() {
        IntakePayloadPutReceipt receipt = receipt(
                IntakePayloadSourceKind.SERVER_MINTED_HUMAN_INPUT,
                IntakePayloadSourceKind.SERVER_MINTED_HUMAN_INPUT.schemaVersion());
        return new IntakePayloadAuthority(
                "PAYLOAD-1",
                "COMMAND-1",
                route(),
                IntakePayloadSourceKind.SERVER_MINTED_HUMAN_INPUT,
                null,
                "ARTIFACT-1",
                IntakePayloadSourceKind.SERVER_MINTED_HUMAN_INPUT.schemaVersion(),
                "urn:payload:1",
                "VERSION-1",
                HASH,
                1,
                receipt,
                NOW);
    }

    private static IntakeCommandAuthority command(
            CommandType commandType, IntakeCommandAuthority.ExecutionDisposition disposition) {
        return new IntakeCommandAuthority(
                "CASE-COMMAND-1",
                "COMMAND-1",
                1,
                commandType,
                route(),
                "PAYLOAD-1",
                HASH,
                0,
                disposition,
                NOW);
    }

    private static IntakePayloadPutReceipt receipt(
            IntakePayloadSourceKind sourceKind, String payloadSchemaVersion) {
        String receiptHash = receiptHash(sourceKind, payloadSchemaVersion);
        return new IntakePayloadPutReceipt(
                IntakePayloadPutReceipt.SCHEMA_VERSION,
                "RECEIPT-1",
                "iput.v1." + HASH,
                "COMMAND-1",
                "TENANT-1",
                "CASE-1",
                "REG-1",
                "ACTOR-1",
                "ACCESS-1",
                sourceKind,
                "ARTIFACT-1",
                payloadSchemaVersion,
                "urn:payload:1",
                "VERSION-1",
                HASH,
                1,
                0,
                receiptHash);
    }

    private static String receiptHash(IntakePayloadSourceKind sourceKind, String payloadSchemaVersion) {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("schema_version", IntakePayloadPutReceipt.SCHEMA_VERSION);
        root.put("receipt_id", "RECEIPT-1");
        root.put("put_idempotency_key", "iput.v1." + HASH);
        root.put("command_id", "COMMAND-1");
        root.put("tenant_surrogate", "TENANT-1");
        root.put("case_id", "CASE-1");
        root.put("registration_id", "REG-1");
        root.put("actor_id", "ACTOR-1");
        root.put("access_session_id", "ACCESS-1");
        root.put("source_kind", sourceKind.name());
        root.put("artifact_id", "ARTIFACT-1");
        root.put("payload_schema_version", payloadSchemaVersion);
        root.put("object_uri", "urn:payload:1");
        root.put("object_version", "VERSION-1");
        root.put("content_sha256", HASH);
        root.put("size_bytes", 1);
        root.put("stored_at_epoch_micros", 0);
        return ContractJson.sha256Hex(root);
    }

    private static IntakeAuthorityRoute route() {
        return new IntakeAuthorityRoute(
                "PARTY-AUTH-1",
                "EPOCH-1",
                "ACCESS-1",
                "REG-1",
                "TENANT-1",
                "CASE-1",
                0,
                1,
                "grt.v1." + "b".repeat(32),
                "ACTOR-1",
                ActorRole.USER,
                HASH,
                "AGENT-1",
                Party.INITIATOR);
    }
}

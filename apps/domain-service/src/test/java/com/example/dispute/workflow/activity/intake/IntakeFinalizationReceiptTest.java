package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.application.intake.IntakeFinalizationReceiptCodec;
import com.example.dispute.workflow.application.intake.IntakeFinalizationOperationKey;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IntakeFinalizationReceiptTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path RECEIPT_FIXTURE = Path.of(
            "..",
            "..",
            "contracts",
            "agent-platform",
            "intake",
            "v2",
            "fixtures",
            "valid",
            "intake-finalization-receipt-valid.json");

    @Test
    void verifiesAndRegeneratesTheFrozenReceiptFixture() throws Exception {
        IntakeFinalizationReceipt fixture =
                MAPPER.readValue(RECEIPT_FIXTURE.toFile(), IntakeFinalizationReceipt.class);

        fixture.requireCanonicalHash();
        assertThat(fixture.operationKey())
                .isEqualTo(
                        "intake.turn.finalize:CASE_P4_SYNTHETIC_1:1:"
                                + "grt.v1.018f6b7ec30a7430982fffc520c8195c:"
                                + "COMMAND_P4_USER_2:"
                                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        IntakeFinalizationReceipt regenerated = IntakeFinalizationReceipt.committed(
                new IntakeFinalizationReceipt.CommitFacts(
                        fixture.operationKey(),
                        fixture.tenantSurrogate(),
                        fixture.caseId(),
                        fixture.roomEpoch(),
                        fixture.threadId(),
                        fixture.actorScopeHash(),
                        fixture.agentSessionId(),
                        fixture.commandId(),
                        fixture.logicalRunId(),
                        fixture.attemptId(),
                        fixture.resultHash(),
                        fixture.proposalHash(),
                        fixture.processRevision(),
                        fixture.roomRevision(),
                        fixture.fencingToken(),
                        fixture.formalMessageId(),
                        fixture.dossierVersion(),
                        fixture.matrixVersion(),
                        fixture.domainEventIds(),
                        fixture.outboxIds(),
                        fixture.committedAt()));

        assertThat(regenerated).isEqualTo(fixture);
        assertThat(regenerated.operationKey()).hasSizeGreaterThan(128);
        JsonNode frozenWire = MAPPER.readTree(RECEIPT_FIXTURE.toFile());
        assertThat(IntakeFinalizationReceiptCodec.canonicalBytes(regenerated))
                .containsExactly(ContractJson.canonicalize(frozenWire));
        assertThat(IntakeFinalizationReceiptCodec.toTree(regenerated).has("matrix_version"))
                .isFalse();
    }

    @Test
    void operationKeyKeepsTheExactFormulaForMaximumContractIdentifiers() {
        String caseId = "C".repeat(128);
        String commandId = "D".repeat(128);
        String resultHash = "a".repeat(64);

        String operationKey = IntakeFinalizationOperationKey.create(
                caseId,
                Long.MAX_VALUE,
                "grt.v1.018f6b7ec30a7430982fffc520c8195c",
                commandId,
                resultHash);

        assertThat(operationKey)
                .isEqualTo(
                        "intake.turn.finalize:"
                                + caseId
                                + ":"
                                + Long.MAX_VALUE
                                + ":grt.v1.018f6b7ec30a7430982fffc520c8195c:"
                                + commandId
                                + ":"
                                + resultHash)
                .hasSizeLessThanOrEqualTo(IntakeFinalizationOperationKey.MAX_KEY_LENGTH);
    }
}

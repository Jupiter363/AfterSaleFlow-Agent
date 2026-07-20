package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IntakeFinalizationReceiptTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path RECEIPT_FIXTURE = Path.of(
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
    }
}

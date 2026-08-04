package com.example.dispute.workflow.targete2e.artifact.finalization;

import com.example.dispute.agentstream.application.AgentRunFinalizationFailure;
import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceipt;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRejectedException;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Delivers a committed Intake formal event directly to its current case workflow. */
public final class TargetE2eIntakeDomainEventLiveRelay {

    private static final String FORMAL_EVENT_SQL =
            """
            select id, sequence_no, event_type, event_json::text as event_json
              from case_timeline_event
             where case_id = :caseId
               and event_type in ('TURN_NEEDS_INPUT', 'TURN_READY_TO_CONFIRM')
               and event_json -> 'receipt' ->> 'logical_run_id' = :logicalRunId
               and event_json -> 'receipt' ->> 'attempt_id' = :attemptId
             order by sequence_no
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CaseProcessLedgerActivities ledgerActivities;
    private final WorkflowClient workflowClient;

    public TargetE2eIntakeDomainEventLiveRelay(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            CaseProcessLedgerActivities ledgerActivities,
            WorkflowClient workflowClient) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.ledgerActivities = Objects.requireNonNull(ledgerActivities, "ledgerActivities");
        this.workflowClient = Objects.requireNonNull(workflowClient, "workflowClient");
    }

    public void relay(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            TargetE2eFinalizationReceipt targetReceipt,
            AgentRunFinalizationReceipt agentReceipt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(targetReceipt, "targetReceipt");
        Objects.requireNonNull(agentReceipt, "agentReceipt");
        requireCommittedRequestFence(request, result, targetReceipt, agentReceipt);

        FormalEvent formalEvent = selectFormalEventRetryably(request);
        IntakeFinalizationReceipt intakeReceipt = decodeReceipt(formalEvent);
        requireFormalReceipt(
                request, result, targetReceipt, agentReceipt, formalEvent, intakeReceipt);

        CaseDomainEventRef event = loadCanonicalEventRetryably(targetReceipt, formalEvent);
        requireCanonicalEvent(targetReceipt, formalEvent, event);

        String workflowId = CaseProcessWorkflowProtocol.caseWorkflowId(
                targetReceipt.tenantSurrogate(), targetReceipt.caseId());
        try {
            CaseProcessWorkflow workflow =
                    workflowClient.newWorkflowStub(CaseProcessWorkflow.class, workflowId);
            // Delivery is intentionally at-least-once. CaseProcessWorkflow deduplicates by sequence
            // and payload identity, including a replay after an acknowledgement loss.
            workflow.domainEventCommitted(event);
        } catch (RuntimeException failure) {
            if (failure instanceof AgentRunFinalizationFailure) {
                throw failure;
            }
            throw new LiveDeliveryFailure(
                    "committed Intake event could not be signalled to CaseProcessWorkflow",
                    failure);
        }
    }

    private FormalEvent selectFormalEventRetryably(ExecuteAgentRunRequest request) {
        try {
            return selectFormalEvent(request);
        } catch (TargetE2eFinalizationRejectedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LiveDeliveryFailure(
                    "committed Intake event selector is temporarily unavailable", failure);
        }
    }

    private FormalEvent selectFormalEvent(ExecuteAgentRunRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                FORMAL_EVENT_SQL,
                new MapSqlParameterSource()
                        .addValue("caseId", request.command().caseId())
                        .addValue("logicalRunId", request.logicalRunId())
                        .addValue("attemptId", request.attemptId()));
        if (rows.size() != 1) {
            throw rejected(
                    "TARGET_E2E_INTAKE_FORMAL_EVENT_NOT_UNIQUE",
                    "committed Intake run must select exactly one formal timeline event");
        }
        Map<String, Object> row = rows.getFirst();
        String eventId = requiredText(row.get("id"), "formal event id");
        String eventType = requiredText(row.get("event_type"), "formal event type");
        String eventJson = requiredText(row.get("event_json"), "formal event json");
        Object sequenceValue = row.get("sequence_no");
        if (!(sequenceValue instanceof Number sequenceNumber)
                || sequenceNumber.longValue() < 1) {
            throw rejected(
                    "TARGET_E2E_INTAKE_FORMAL_EVENT_INVALID",
                    "formal timeline event sequence is invalid");
        }
        return new FormalEvent(eventId, sequenceNumber.longValue(), eventType, eventJson);
    }

    private IntakeFinalizationReceipt decodeReceipt(FormalEvent formalEvent) {
        try {
            JsonNode event = objectMapper.readTree(formalEvent.eventJson());
            String encodedType = event.required("event_type").textValue();
            if (!formalEvent.eventType().equals(encodedType)) {
                throw rejected(
                        "TARGET_E2E_INTAKE_FORMAL_EVENT_INVALID",
                        "formal event type conflicts with its canonical body");
            }
            IntakeFinalizationReceipt receipt = objectMapper.treeToValue(
                    event.required("receipt"), IntakeFinalizationReceipt.class);
            receipt.requireCanonicalHash();
            return receipt;
        } catch (TargetE2eFinalizationRejectedException failure) {
            throw failure;
        } catch (JsonProcessingException
                | IllegalArgumentException
                | IntakeFinalizationRejectedException failure) {
            throw rejected(
                    "TARGET_E2E_INTAKE_FORMAL_RECEIPT_INVALID",
                    "formal Intake receipt is absent, malformed, or non-canonical",
                    failure);
        }
    }

    private CaseDomainEventRef loadCanonicalEvent(
            TargetE2eFinalizationReceipt targetReceipt, FormalEvent formalEvent) {
        List<CaseDomainEventRef> events = ledgerActivities.loadDomainEvents(
                new LoadSequenceRange(
                        "load-sequence-range.v1",
                        targetReceipt.tenantSurrogate(),
                        targetReceipt.caseId(),
                        formalEvent.sequenceNo(),
                        formalEvent.sequenceNo(),
                        1));
        if (events.size() != 1) {
            throw rejected(
                    "TARGET_E2E_INTAKE_CANONICAL_EVENT_NOT_UNIQUE",
                    "formal Intake event exact range did not load one canonical event");
        }
        return events.getFirst();
    }

    private CaseDomainEventRef loadCanonicalEventRetryably(
            TargetE2eFinalizationReceipt targetReceipt, FormalEvent formalEvent) {
        try {
            return loadCanonicalEvent(targetReceipt, formalEvent);
        } catch (TargetE2eFinalizationRejectedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LiveDeliveryFailure(
                    "canonical Intake event ledger is temporarily unavailable", failure);
        }
    }

    private static void requireCommittedRequestFence(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            TargetE2eFinalizationReceipt targetReceipt,
            AgentRunFinalizationReceipt agentReceipt) {
        targetReceipt.requireCanonicalHash();
        if (request.command().roomType() != RoomType.INTAKE
                || targetReceipt.roomType() != RoomType.INTAKE
                || !request.command().tenantSurrogate().equals(targetReceipt.tenantSurrogate())
                || !request.command().caseId().equals(targetReceipt.caseId())
                || request.command().roomEpoch() != targetReceipt.roomEpoch()
                || request.command().processRevision() != targetReceipt.processRevision()
                || !request.logicalRunId().equals(targetReceipt.logicalRunId())
                || !request.attemptId().equals(targetReceipt.attemptId())
                || !result.logicalRunId().equals(targetReceipt.logicalRunId())
                || !result.attemptId().equals(targetReceipt.attemptId())
                || !result.resultHash().equals(targetReceipt.resultHash())
                || !agentReceipt.agentRunId().equals(request.agentRunId())
                || !agentReceipt.logicalRunId().equals(request.logicalRunId())
                || !agentReceipt.attemptId().equals(request.attemptId())
                || agentReceipt.attemptNo() != request.attemptNo()
                || agentReceipt.fencingToken() != targetReceipt.roomFencingToken()
                || !agentReceipt.finalResultHash().equals(result.resultHash())) {
            throw rejected(
                    "TARGET_E2E_INTAKE_LIVE_RELAY_FENCE_MISMATCH",
                    "committed target receipt conflicts with the Intake execution fence");
        }
    }

    private static void requireFormalReceipt(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            TargetE2eFinalizationReceipt targetReceipt,
            AgentRunFinalizationReceipt agentReceipt,
            FormalEvent formalEvent,
            IntakeFinalizationReceipt receipt) {
        if (!List.of("TURN_NEEDS_INPUT", "TURN_READY_TO_CONFIRM")
                        .contains(formalEvent.eventType())
                || !receipt.domainEventIds().equals(List.of(formalEvent.eventId()))
                || !receipt.tenantSurrogate().equals(request.command().tenantSurrogate())
                || !receipt.caseId().equals(request.command().caseId())
                || receipt.roomEpoch() != request.command().roomEpoch()
                || receipt.processRevision() != request.command().processRevision()
                || !receipt.commandId().equals(request.command().commandId())
                || receipt.fencingToken() != targetReceipt.roomFencingToken()
                || receipt.fencingToken() != agentReceipt.fencingToken()
                || !receipt.logicalRunId().equals(request.logicalRunId())
                || !receipt.attemptId().equals(request.attemptId())
                || !receipt.resultHash().equals(result.resultHash())
                || !receipt.proposalHash().equals(targetReceipt.proposalHash())) {
            throw rejected(
                    "TARGET_E2E_INTAKE_FORMAL_RECEIPT_MISMATCH",
                    "formal Intake receipt conflicts with the committed execution identity");
        }
    }

    private static void requireCanonicalEvent(
            TargetE2eFinalizationReceipt targetReceipt,
            FormalEvent formalEvent,
            CaseDomainEventRef event) {
        if (!event.eventId().equals(formalEvent.eventId())
                || event.caseEventSequence() != formalEvent.sequenceNo()
                || !event.eventType().equals(formalEvent.eventType())
                || !event.tenantSurrogate().equals(targetReceipt.tenantSurrogate())
                || !event.caseId().equals(targetReceipt.caseId())
                || event.roomType() != RoomType.INTAKE
                || event.roomEpoch() != targetReceipt.roomEpoch()) {
            throw rejected(
                    "TARGET_E2E_INTAKE_CANONICAL_EVENT_MISMATCH",
                    "canonical domain event conflicts with the selected formal event");
        }
    }

    private static String requiredText(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw rejected(
                    "TARGET_E2E_INTAKE_FORMAL_EVENT_INVALID", field + " is missing");
        }
        return text;
    }

    private static TargetE2eFinalizationRejectedException rejected(
            String code, String message) {
        return new TargetE2eFinalizationRejectedException(code, message);
    }

    private static TargetE2eFinalizationRejectedException rejected(
            String code, String message, Throwable cause) {
        return new TargetE2eFinalizationRejectedException(code, message, cause);
    }

    private record FormalEvent(
            String eventId, long sequenceNo, String eventType, String eventJson) {}

    private static final class LiveDeliveryFailure extends RuntimeException
            implements AgentRunFinalizationFailure {

        private static final String CODE = "TargetE2eIntakeDomainEventLiveDeliveryRetryable";

        private LiveDeliveryFailure(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }
}

package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

@ActivityInterface
public interface CaseProcessLedgerActivities {

    @ActivityMethod(name = "LoadCaseCommands")
    List<CaseCommandRef> loadCaseCommands(LoadSequenceRange request);

    @ActivityMethod(name = "LoadDomainEvents")
    List<CaseDomainEventRef> loadDomainEvents(LoadSequenceRange request);

    @ActivityMethod(name = "ReportCaseProcessSequenceGap")
    void reportSequenceGap(SequenceGapReport report);

    enum SequenceStream {
        COMMAND,
        DOMAIN_EVENT
    }

    record LoadSequenceRange(
            String schemaVersion,
            String tenantSurrogate,
            String caseId,
            long fromSequenceInclusive,
            long toSequenceInclusive,
            int limit) {

        public LoadSequenceRange {
            if (!"load-sequence-range.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be load-sequence-range.v1");
            }
            requireText(tenantSurrogate, "tenantSurrogate");
            requireText(caseId, "caseId");
            if (fromSequenceInclusive < 1
                    || toSequenceInclusive < fromSequenceInclusive) {
                throw new IllegalArgumentException("sequence range is invalid");
            }
            if (limit < 1 || limit > 128) {
                throw new IllegalArgumentException("limit must be between 1 and 128");
            }
            if (toSequenceInclusive - fromSequenceInclusive + 1 > limit) {
                throw new IllegalArgumentException("sequence range exceeds limit");
            }
        }
    }

    record SequenceGapReport(
            String schemaVersion,
            String tenantSurrogate,
            String caseId,
            String workflowId,
            String workflowRunId,
            SequenceStream stream,
            long expectedSequence,
            long highestObservedSequence,
            int recoveryAttempts,
            String reasonCode) {

        public SequenceGapReport {
            if (!"sequence-gap-report.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be sequence-gap-report.v1");
            }
            requireText(tenantSurrogate, "tenantSurrogate");
            requireText(caseId, "caseId");
            requireText(workflowId, "workflowId");
            requireText(workflowRunId, "workflowRunId");
            if (stream == null) {
                throw new IllegalArgumentException("stream must not be null");
            }
            if (expectedSequence < 1 || highestObservedSequence < expectedSequence) {
                throw new IllegalArgumentException("gap sequence is invalid");
            }
            if (recoveryAttempts < 1) {
                throw new IllegalArgumentException("recoveryAttempts must be positive");
            }
            requireText(reasonCode, "reasonCode");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

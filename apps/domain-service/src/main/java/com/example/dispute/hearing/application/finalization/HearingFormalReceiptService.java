package com.example.dispute.hearing.application.finalization;

import com.example.dispute.hearing.domain.HearingDomainReceipt;
import com.example.dispute.hearing.domain.HearingFormalFinalizer;
import com.example.dispute.workflow.activity.hearing.HearingDomainReceiptAdapter;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import java.util.Objects;

/**
 * Dormant application seam from Java formal commits to bounded Temporal receipt payloads. It is
 * intentionally not a Spring component until a later promotion admits a formal Hearing Activity.
 */
public final class HearingFormalReceiptService {

    private final HearingFormalFinalizer finalizer;

    public HearingFormalReceiptService(HearingFormalFinalizer finalizer) {
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
    }

    public HearingPartyTerminalReceipt appendPartyAction(
            HearingFormalFinalizer.ActionCommand command) {
        Objects.requireNonNull(command, "command");
        if (!command.actionType().isPartyAction()) {
            throw new IllegalArgumentException("party receipt requires a party action command");
        }
        HearingDomainReceipt receipt = finalizer.appendAction(command);
        return HearingDomainReceiptAdapter.party(
                receipt,
                command.requestId(),
                command.participantId(),
                HearingPartyTerminalReceipt.TerminalStatus.valueOf(
                        command.submissionStatus().name()));
    }

    public HearingPartyTerminalReceipt adoptPartyAction(
            HearingFormalFinalizer.AdoptPartyActionCommand command) {
        Objects.requireNonNull(command, "command");
        HearingDomainReceipt receipt = finalizer.adoptPartyAction(command);
        return HearingDomainReceiptAdapter.party(receipt, command.requestId(), command.participantId(),
                HearingPartyTerminalReceipt.TerminalStatus.valueOf(command.submissionStatus().name()));
    }

    public HearingStageReceipt appendGeneratedAction(
            HearingFormalFinalizer.ActionCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.actionType().isPartyAction()) {
            throw new IllegalArgumentException("generated receipt cannot use a party action command");
        }
        return stage(finalizer.appendAction(command));
    }

    public HearingStageReceipt advanceStage(HearingFormalFinalizer.StageCommand command) {
        return stage(finalizer.advanceStage(Objects.requireNonNull(command, "command")));
    }

    public HearingStageReceipt finalizeMatrixSynthesis(
            HearingFormalFinalizer.MatrixSynthesisCommand command) {
        return stage(finalizer.finalizeMatrixSynthesis(Objects.requireNonNull(command, "command")));
    }

    public HearingStageReceipt freezeDossier(HearingFormalFinalizer.DossierCommand command) {
        return stage(finalizer.freezeDossier(Objects.requireNonNull(command, "command")));
    }

    public HearingStageReceipt finalizeJudgeV1(HearingFormalFinalizer.DecisionCommand command) {
        return stage(finalizer.finalizeJudgeV1(Objects.requireNonNull(command, "command")));
    }

    public HearingStageReceipt finalizeJuryReview(
            HearingFormalFinalizer.DecisionCommand command) {
        return stage(finalizer.finalizeJuryReview(Objects.requireNonNull(command, "command")));
    }

    public HearingStageReceipt finalizeJudgeV2(HearingFormalFinalizer.DecisionCommand command) {
        return stage(finalizer.finalizeJudgeV2(Objects.requireNonNull(command, "command")));
    }

    public HearingStageReceipt commitHandoff(HearingFormalFinalizer.HandoffCommand command) {
        return stage(finalizer.commitHandoff(Objects.requireNonNull(command, "command")));
    }

    public HearingStageReceipt commitClosure(HearingFormalFinalizer.ClosureCommand command) {
        return stage(finalizer.commitClosure(Objects.requireNonNull(command, "command")));
    }

    private static HearingStageReceipt stage(HearingDomainReceipt receipt) {
        return HearingDomainReceiptAdapter.stage(
                Objects.requireNonNull(receipt, "formal finalizer returned no receipt"));
    }
}

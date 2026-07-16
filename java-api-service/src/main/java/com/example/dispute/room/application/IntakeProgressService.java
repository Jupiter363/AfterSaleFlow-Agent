package com.example.dispute.room.application;

import com.example.dispute.common.exception.BadRequestException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakePartyCompletionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakePartyCompletionRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Derives party-specific intake access without duplicating the case-level stage. */
@Service
public class IntakeProgressService {

    private static final String COMPLETED = "COMPLETED";
    private static final String TIMED_OUT = "TIMED_OUT";

    private final FulfillmentCaseRepository caseRepository;
    private final CaseIntakePartyCompletionRepository repository;
    private final Clock clock;

    public IntakeProgressService(
            FulfillmentCaseRepository caseRepository,
            CaseIntakePartyCompletionRepository repository,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public IntakeStatusView status(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository.findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertCaseActor(dispute, actor, true);
        return status(dispute, actor);
    }

    public IntakeStatusView status(FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        ActorRole initiator = dispute.getInitiatorRole();
        ActorRole respondent = dispute.getRespondentRole();
        Optional<CaseIntakePartyCompletionEntity> initiatorTerminal = terminal(dispute, initiator);
        Optional<CaseIntakePartyCompletionEntity> respondentTerminal = terminal(dispute, respondent);
        boolean legacy = initiatorTerminal.isEmpty()
                && respondentTerminal.isEmpty()
                && !isIntakeCaseStatus(dispute.getCaseStatus());
        String initiatorStatus = legacy ? COMPLETED : terminalStatus(initiatorTerminal, "OPEN");
        String respondentStatus;
        if (legacy) {
            respondentStatus = COMPLETED;
        } else if (respondentTerminal.isPresent()) {
            respondentStatus = respondentTerminal.orElseThrow().getCompletionStatus();
        } else if (initiatorTerminal.isEmpty()) {
            respondentStatus = "LOCKED";
        } else if (deadlineExpired(dispute)) {
            respondentStatus = TIMED_OUT;
        } else {
            respondentStatus = "OPEN";
        }
        boolean party = isParty(actor.role());
        boolean actorCompleted = party
                && (legacy
                        || terminal(dispute, actor.role())
                                .filter(item -> COMPLETED.equals(item.getCompletionStatus()))
                                .isPresent());
        return new IntakeStatusView(
                dispute.getId(),
                initiator,
                respondent,
                initiatorStatus,
                respondentStatus,
                actorCompleted,
                canUseIntake(dispute, actor, initiatorTerminal, respondentTerminal, legacy),
                canEnterEvidence(dispute, actor, legacy),
                dispute.getCurrentDeadlineAt());
    }

    public CaseIntakePartyCompletionEntity completeInitiator(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor, OffsetDateTime now) {
        assertRoleOrTrusted(dispute, actor, dispute.getInitiatorRole());
        return terminal(dispute, dispute.getInitiatorRole())
                .map(existing -> requireCompleted(existing, dispute.getInitiatorRole()))
                .orElseGet(
                        () -> saveTerminal(
                                dispute,
                                dispute.getInitiatorRole(),
                                partyId(dispute, dispute.getInitiatorRole()),
                                COMPLETED,
                                now,
                                actor.actorId()));
    }

    public CaseIntakePartyCompletionEntity completeRespondent(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor, OffsetDateTime now) {
        ActorRole respondent = dispute.getRespondentRole();
        assertRoleOrTrusted(dispute, actor, respondent);
        if (!isCompleted(dispute, dispute.getInitiatorRole())) {
            throw new BadRequestException(
                    "respondent intake is not open",
                    Map.of("case_id", dispute.getId(), "respondent_role", respondent.name()));
        }
        if (deadlineExpired(dispute)) {
            throw new BadRequestException(
                    "evidence deadline has expired",
                    Map.of("case_id", dispute.getId(), "deadline_at", dispute.getCurrentDeadlineAt()));
        }
        return terminal(dispute, respondent)
                .map(existing -> requireCompleted(existing, respondent))
                .orElseGet(
                        () -> saveTerminal(
                                dispute,
                                respondent,
                                partyId(dispute, respondent),
                                COMPLETED,
                                now,
                                actor.actorId()));
    }

    public void markRespondentTimedOut(FulfillmentCaseEntity dispute, OffsetDateTime now) {
        ActorRole respondent = dispute.getRespondentRole();
        if (repository.countByCaseId(dispute.getId()) == 0 || terminal(dispute, respondent).isPresent()) {
            return;
        }
        saveTerminal(
                dispute,
                respondent,
                partyId(dispute, respondent),
                TIMED_OUT,
                now,
                "evidence-deadline");
    }

    public boolean isCompleted(String caseId, ActorRole role) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        return isCompleted(dispute, role);
    }

    public boolean isCompleted(FulfillmentCaseEntity dispute, ActorRole role) {
        return repository
                .findByCaseIdAndParticipantRoleAndParticipantId(
                        dispute.getId(), role, dispute.partyAssignment().idFor(role))
                .filter(item -> COMPLETED.equals(item.getCompletionStatus()))
                .isPresent();
    }

    public void assertIntakeRead(FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        assertCaseActor(dispute, actor, true);
        if (privileged(actor.role())) {
            return;
        }
        ActorRole initiator = dispute.getInitiatorRole();
        if (actor.role() == initiator || isCompleted(dispute, initiator)) {
            return;
        }
        throw new ForbiddenException("respondent intake is not open yet");
    }

    public void assertIntakePost(FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        if (privileged(actor.role())) {
            return;
        }
        assertCaseActor(dispute, actor, false);
        ActorRole initiator = dispute.getInitiatorRole();
        if (actor.role() == initiator) {
            if (!isCompleted(dispute, initiator)) {
                return;
            }
            throw new ForbiddenException("initiator intake has already completed");
        }
        if (!isCompleted(dispute, initiator)) {
            throw new ForbiddenException("respondent intake is not open yet");
        }
        if (terminal(dispute, actor.role()).isPresent() || deadlineExpired(dispute)) {
            throw new ForbiddenException("respondent intake is already closed");
        }
    }

    public void assertEvidenceAccess(FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        assertEvidenceAccess(dispute, actor, false);
    }

    public void assertEvidenceReadAccess(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        assertEvidenceAccess(dispute, actor, true);
    }

    private void assertEvidenceAccess(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor, boolean readOnly) {
        assertCaseActor(dispute, actor, true);
        if (privileged(actor.role())) {
            return;
        }
        if (repository.countByCaseId(dispute.getId()) == 0
                && !isIntakeCaseStatus(dispute.getCaseStatus())) {
            return;
        }
        ActorRole respondent = dispute.getRespondentRole();
        boolean stageReached =
                readOnly
                        ? evidenceReadStageReached(dispute.getCaseStatus())
                        : evidenceStageReached(dispute.getCaseStatus());
        if (stageReached
                && isCompleted(dispute, dispute.getInitiatorRole())
                && isCompleted(dispute, respondent)) {
            return;
        }
        throw new ForbiddenException(
                "evidence opens only after both parties complete their intake statements");
    }

    @Transactional(readOnly = true)
    public void assertEvidenceAccess(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository.findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertEvidenceAccess(dispute, actor);
    }

    @Transactional(readOnly = true)
    public void assertEvidenceReadAccess(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository.findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertEvidenceReadAccess(dispute, actor);
    }

    private boolean canUseIntake(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor actor,
            Optional<CaseIntakePartyCompletionEntity> initiatorTerminal,
            Optional<CaseIntakePartyCompletionEntity> respondentTerminal,
            boolean legacy) {
        if (privileged(actor.role())) {
            return true;
        }
        if (!isParty(actor.role()) || legacy) {
            return false;
        }
        if (actor.role() == dispute.getInitiatorRole()) {
            return initiatorTerminal.isEmpty();
        }
        return initiatorTerminal.filter(item -> COMPLETED.equals(item.getCompletionStatus())).isPresent()
                && respondentTerminal.isEmpty()
                && !deadlineExpired(dispute);
    }

    private boolean canEnterEvidence(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor, boolean legacy) {
        if (privileged(actor.role())) {
            return dispute.getCaseStatus() == CaseStatus.EVIDENCE_OPEN;
        }
        return isParty(actor.role())
                && dispute.getCaseStatus() == CaseStatus.EVIDENCE_OPEN
                && (legacy
                        || isCompleted(dispute, dispute.getInitiatorRole())
                                && isCompleted(dispute, dispute.getRespondentRole()));
    }

    private Optional<CaseIntakePartyCompletionEntity> terminal(
            FulfillmentCaseEntity dispute, ActorRole role) {
        return repository.findByCaseIdAndParticipantRoleAndParticipantId(
                dispute.getId(), role, dispute.partyAssignment().idFor(role));
    }

    private CaseIntakePartyCompletionEntity saveTerminal(
            FulfillmentCaseEntity dispute,
            ActorRole role,
            String participantId,
            String status,
            OffsetDateTime now,
            String actorId) {
        return repository.save(
                CaseIntakePartyCompletionEntity.terminal(
                        "INTAKE_COMPLETE_" + UUID.randomUUID().toString().replace("-", ""),
                        dispute.getId(),
                        role,
                        participantId,
                        status,
                        now.toInstant(),
                        actorId));
    }

    private static CaseIntakePartyCompletionEntity requireCompleted(
            CaseIntakePartyCompletionEntity existing, ActorRole role) {
        if (!COMPLETED.equals(existing.getCompletionStatus())) {
            throw new BadRequestException(
                    "intake participation has already timed out",
                    Map.of("participant_role", role.name()));
        }
        return existing;
    }

    private void assertRoleOrTrusted(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor, ActorRole expectedRole) {
        if (privileged(actor.role())) {
            return;
        }
        assertCaseActor(dispute, actor, false);
        if (actor.role() != expectedRole) {
            throw new ForbiddenException("party cannot complete the other role's intake");
        }
    }

    private static void assertCaseActor(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor, boolean allowPrivileged) {
        if (allowPrivileged && privileged(actor.role())) {
            return;
        }
        if (!isParty(actor.role())
                || dispute.partyAssignment()
                        .resolve(actor.actorId(), actor.role())
                        .isEmpty()) {
            throw new ForbiddenException("actor is not a party to this case");
        }
    }

    private boolean deadlineExpired(FulfillmentCaseEntity dispute) {
        return dispute.getCurrentDeadlineAt() != null
                && !OffsetDateTime.now(clock).isBefore(dispute.getCurrentDeadlineAt());
    }

    private static String terminalStatus(
            Optional<CaseIntakePartyCompletionEntity> terminal, String fallback) {
        return terminal.map(CaseIntakePartyCompletionEntity::getCompletionStatus).orElse(fallback);
    }

    private static boolean isIntakeCaseStatus(CaseStatus status) {
        return status == CaseStatus.INTAKE_PENDING
                || status == CaseStatus.INTAKE_IN_PROGRESS
                || status == CaseStatus.WAITING_SLOT_COMPLETION
                || status == CaseStatus.INTAKE_COMPLETED;
    }

    private static boolean evidenceStageReached(CaseStatus status) {
        return switch (status) {
            case EVIDENCE_OPEN,
                    EVIDENCE_SEALED,
                    DOSSIER_BUILDING,
                    DOSSIER_BUILT,
                    ROUTED,
                    HEARING,
                    HEARING_OPEN,
                    WAITING_EVIDENCE -> true;
            default -> false;
        };
    }

    private static boolean evidenceReadStageReached(CaseStatus status) {
        if (evidenceStageReached(status)) {
            return true;
        }
        return switch (status) {
            case SETTLEMENT_PENDING,
                    DRAFT_READY,
                    DELIBERATION_RUNNING,
                    REVIEW_PENDING,
                    REMEDY_PLANNED,
                    WAITING_HUMAN_REVIEW,
                    MANUAL_HANDOFF,
                    APPROVED_FOR_EXECUTION,
                    EXECUTING,
                    CLOSED -> true;
            default -> false;
        };
    }

    private static boolean isParty(ActorRole role) {
        return role == ActorRole.USER || role == ActorRole.MERCHANT;
    }

    private static boolean privileged(ActorRole role) {
        return !isParty(role);
    }

    private static String partyId(FulfillmentCaseEntity dispute, ActorRole role) {
        return dispute.partyAssignment().idFor(role);
    }
}

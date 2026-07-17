package com.example.dispute.workflow.application.command;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import java.util.EnumSet;
import java.util.List;

final class CaseCommandAuthorization {

    private static final EnumSet<CommandType> PARTY_COMMANDS =
            EnumSet.of(
                    CommandType.INTAKE_MESSAGE,
                    CommandType.INTAKE_CONFIRM,
                    CommandType.INTAKE_CANCEL,
                    CommandType.EVIDENCE_SUBMIT,
                    CommandType.PARTY_EVIDENCE_COMPLETE,
                    CommandType.HEARING_STATEMENT,
                    CommandType.HEARING_EVIDENCE_BATCH);

    private CaseCommandAuthorization() {}

    static ActorRef authorize(
            FulfillmentCaseEntity disputeCase,
            AcceptCaseCommand command,
            AuthenticatedActor actor) {
        if (actor == null || actor.actorId() == null || actor.role() == null) {
            throw new ForbiddenException("authenticated actor is required");
        }
        switch (actor.role()) {
            case USER -> assertParty(
                    actor.actorId(), disputeCase.getUserId(), command.commandType());
            case MERCHANT -> assertParty(
                    actor.actorId(), disputeCase.getMerchantId(), command.commandType());
            case PLATFORM_REVIEWER -> {
                if (command.commandType() != CommandType.REVIEW_DECISION) {
                    throw new ForbiddenException(
                            "platform reviewer cannot submit this case command");
                }
            }
            case ADMIN, SYSTEM -> {
                // These trusted roles are still bound into the immutable request hash.
            }
            case CUSTOMER_SERVICE ->
                    throw new ForbiddenException(
                            "customer service is not a supported command actor");
        }

        var contractRole =
                com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.valueOf(
                        actor.role().name());
        String scope =
                "case:"
                        + disputeCase.getId()
                        + ":command:"
                        + command.commandType().name();
        return new ActorRef(actor.actorId(), contractRole, List.of(scope));
    }

    private static void assertParty(
            String actualActorId, String expectedActorId, CommandType commandType) {
        if (!actualActorId.equals(expectedActorId) || !PARTY_COMMANDS.contains(commandType)) {
            throw new ForbiddenException("actor is not authorized for this case command");
        }
    }
}

package com.example.dispute.casecore.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotently imports dispute candidates from trusted platform adapters.
 *
 * <p>The external source pair is the business idempotency key; request keys may
 * change across adapter retries and therefore cannot be the sole identity.
 */
@Service
public class DisputeImportService {

    private final FulfillmentCaseRepository repository;
    private final Clock clock;

    public DisputeImportService(FulfillmentCaseRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public ImportedDisputeView importDispute(
            ImportDisputeCommand command,
            AuthenticatedActor actor,
            String idempotencyKey) {
        if (actor.role() != ActorRole.SYSTEM) {
            throw new SecurityException("external dispute import requires service identity");
        }
        requireText(idempotencyKey, "idempotencyKey");
        return repository
                .findBySourceSystemAndExternalCaseRef(
                        command.sourceSystem(), command.externalCaseReference())
                .map(DisputeImportService::view)
                .orElseGet(
                        () -> {
                            FulfillmentCaseEntity entity =
                                    FulfillmentCaseEntity.imported(
                                            "CASE_" + compactUuid(),
                                            command.orderReference(),
                                            command.afterSalesReference(),
                                            command.logisticsReference(),
                                            command.userId(),
                                            command.merchantId(),
                                            idempotencyKey,
                                            command.disputeType(),
                                            command.title(),
                                            command.description(),
                                            command.riskLevel(),
                                            command.caseStatus(),
                                            command.currentRoom(),
                                            command.currentDeadlineAt(),
                                            command.sourceSystem(),
                                            command.externalCaseReference(),
                                            actor.actorId());
                            return view(repository.save(entity));
                        });
    }

    private static ImportedDisputeView view(FulfillmentCaseEntity entity) {
        return new ImportedDisputeView(
                entity.getId(),
                entity.getSourceType().name(),
                entity.getSourceSystem(),
                entity.getExternalCaseRef(),
                entity.getCaseStatus(),
                entity.getCurrentRoom(),
                entity.getCurrentDeadlineAt());
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

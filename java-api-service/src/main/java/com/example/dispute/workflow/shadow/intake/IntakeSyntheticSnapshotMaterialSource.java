package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher.OwnMessage;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.ActivityAuthority;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Supplies private snapshot bodies that Domain PostgreSQL deliberately does not persist. */
public interface IntakeSyntheticSnapshotMaterialSource {

    SnapshotMaterial load(SnapshotMaterialQuery query);

    record SnapshotMaterialQuery(
            ActivityAuthority authority,
            IntakeGraphThreadBinding threadBinding,
            long authoritativeRoomRevision) {

        public SnapshotMaterialQuery {
            Objects.requireNonNull(authority, "authority must not be null");
            Objects.requireNonNull(threadBinding, "threadBinding must not be null");
            if (authoritativeRoomRevision < 0) {
                throw new IllegalArgumentException("authoritativeRoomRevision must not be negative");
            }
        }
    }

    record SnapshotMaterial(
            String snapshotId,
            long domainRevision,
            long projectionRevision,
            List<String> sourceRefs,
            JsonNode initialCaseFacts,
            JsonNode shareableProjection,
            List<OwnMessage> ownMessages,
            JsonNode currentDossier,
            Instant createdAt) {

        public SnapshotMaterial {
            Objects.requireNonNull(snapshotId, "snapshotId must not be null");
            if (domainRevision < 0 || projectionRevision < 0) {
                throw new IllegalArgumentException("snapshot revisions must not be negative");
            }
            sourceRefs = List.copyOf(Objects.requireNonNull(sourceRefs, "sourceRefs must not be null"));
            initialCaseFacts = Objects.requireNonNull(initialCaseFacts, "initialCaseFacts must not be null")
                    .deepCopy();
            shareableProjection = Objects.requireNonNull(
                            shareableProjection, "shareableProjection must not be null")
                    .deepCopy();
            ownMessages = List.copyOf(Objects.requireNonNull(ownMessages, "ownMessages must not be null"));
            currentDossier = Objects.requireNonNull(currentDossier, "currentDossier must not be null")
                    .deepCopy();
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }

        @Override
        public JsonNode initialCaseFacts() {
            return initialCaseFacts.deepCopy();
        }

        @Override
        public JsonNode shareableProjection() {
            return shareableProjection.deepCopy();
        }

        @Override
        public JsonNode currentDossier() {
            return currentDossier.deepCopy();
        }
    }
}

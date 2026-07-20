package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher.PublishRequest;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher.StoredPayload;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher.MessageRole;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher.OwnMessage;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher.SnapshotRequest;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingConflictException;
import com.example.dispute.workflow.application.intake.IntakeTurnEventPublisher;
import com.example.dispute.workflow.application.intake.IntakeTurnEventPublisher.EventRequest;
import com.example.dispute.workflow.application.intake.IntakeTurnEventPublisher.SourceType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntakeSnapshotAndEventPublisherTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    @Test
    void publishesTheFrozenPrivateSnapshotAndReplaysOnlyTheSameInitialization() throws Exception {
        var store = new IntakeTestFixtures.SingleBindingStore();
        var objects = new CapturingPublisher();
        var publisher = new IntakeDomainSnapshotPublisher(objects, store);
        SnapshotRequest request = snapshotRequest();

        var created = publisher.publish(request);
        var replayed = publisher.publish(request);
        var fixture = fixture("intake-domain-snapshot-valid.json");

        assertThat(created.created()).isTrue();
        assertThat(replayed.created()).isFalse();
        assertThat(created.value().payloadRef().sha256())
                .isEqualTo(fixture.required("snapshot_hash").asText());
        assertThat(MAPPER.readTree(objects.last.canonicalPayload())).isEqualTo(fixture);
        assertThat(IntakeDomainSnapshotPublisher.operationKey(request))
                .isEqualTo(
                        "intake.snapshot.publish:CASE_P4_SYNTHETIC_1:1:"
                                + "52f01901287fe5e5465ddcd7d7baf9074aa77e3d88a64da747bf1f530916a5d2:4");

        SnapshotRequest drift = new SnapshotRequest(
                "SNAPSHOT_P4_USER_2",
                request.threadBinding(),
                request.domainRevision() + 1,
                request.roomRevision(),
                request.projectionRevision(),
                request.sourceRefs(),
                request.initialCaseFacts(),
                request.shareableProjection(),
                request.ownMessages(),
                request.currentDossier(),
                request.createdAt());
        assertThatThrownBy(() -> publisher.publish(drift))
                .isInstanceOf(IntakeGraphBindingConflictException.class);
    }

    @Test
    void rejectsNestedLegacyMemoryAndCrossAudienceMessagesBeforePublishing() {
        var objects = new CapturingPublisher();
        var publisher = new IntakeDomainSnapshotPublisher(
                objects, new IntakeTestFixtures.SingleBindingStore());
        SnapshotRequest valid = snapshotRequest();
        var unsafeDossier = (ObjectNode) valid.currentDossier();
        unsafeDossier.putObject("nested").putObject("memory_frame").put("secret", true);
        SnapshotRequest unsafe = new SnapshotRequest(
                valid.snapshotId(),
                valid.threadBinding(),
                valid.domainRevision(),
                valid.roomRevision(),
                valid.projectionRevision(),
                valid.sourceRefs(),
                valid.initialCaseFacts(),
                valid.shareableProjection(),
                valid.ownMessages(),
                unsafeDossier,
                valid.createdAt());

        assertThatThrownBy(() -> publisher.publish(unsafe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory_frame");

        SnapshotRequest crossAudience = new SnapshotRequest(
                valid.snapshotId(),
                valid.threadBinding(),
                valid.domainRevision(),
                valid.roomRevision(),
                valid.projectionRevision(),
                valid.sourceRefs(),
                valid.initialCaseFacts(),
                valid.shareableProjection(),
                List.of(
                        new OwnMessage(
                                "MESSAGE_P4_USER_1",
                                MessageRole.HUMAN,
                                Audience.MERCHANT,
                                1,
                                "Synthetic order arrived damaged.",
                                "1".repeat(64))),
                valid.currentDossier(),
                valid.createdAt());
        assertThatThrownBy(() -> publisher.publish(crossAudience))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audience");
        assertThat(objects.calls).isZero();
    }

    @Test
    void publishesTheFrozenOrderedEventWithoutPersistingItsPrivateText() throws Exception {
        var store = new IntakeTestFixtures.SingleBindingStore();
        store.bindInitialSnapshot(IntakeTestFixtures.snapshot(IntakeTestFixtures.binding()));
        var objects = new CapturingPublisher();
        var publisher = new IntakeTurnEventPublisher(objects, store);
        EventRequest request = eventRequest();

        var created = publisher.publish(request);
        var replayed = publisher.publish(request);
        var fixture = fixture("intake-turn-event-valid.json");

        assertThat(created.created()).isTrue();
        assertThat(replayed.created()).isFalse();
        assertThat(created.value().payloadRef().sha256())
                .isEqualTo(fixture.required("event_hash").asText());
        assertThat(MAPPER.readTree(objects.last.canonicalPayload())).isEqualTo(fixture);
        assertThat(MAPPER.valueToTree(created.value()).findValue("text")).isNull();
    }

    @Test
    void rejectsEventAudienceDriftBeforePublishing() {
        var store = new IntakeTestFixtures.SingleBindingStore();
        store.bindInitialSnapshot(IntakeTestFixtures.snapshot(IntakeTestFixtures.binding()));
        var objects = new CapturingPublisher();
        var publisher = new IntakeTurnEventPublisher(objects, store);
        EventRequest valid = eventRequest();
        EventRequest drift = new EventRequest(
                valid.eventId(),
                valid.messageId(),
                valid.threadBinding(),
                valid.sequenceNo(),
                valid.domainRevision(),
                Audience.MERCHANT,
                valid.sourceType(),
                valid.text(),
                valid.sourceRefs(),
                valid.occurredAt(),
                valid.publishedAt());

        assertThatThrownBy(() -> publisher.publish(drift))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audience");
        assertThat(objects.calls).isZero();
    }

    private static SnapshotRequest snapshotRequest() {
        var binding = IntakeTestFixtures.binding();
        var initialFacts = MAPPER.createObjectNode();
        initialFacts.put("initiator_role", "USER");
        initialFacts.put("form_description", "Synthetic order arrived damaged.");
        var projection = MAPPER.createObjectNode().put("intake_status", "OPEN");
        var dossier = MAPPER.createObjectNode().put("schema_version", "intake-dossier.v2");
        return new SnapshotRequest(
                "SNAPSHOT_P4_USER_1",
                binding,
                4,
                2,
                4,
                List.of("MESSAGE_P4_USER_1"),
                initialFacts,
                projection,
                List.of(
                        new OwnMessage(
                                "MESSAGE_P4_USER_1",
                                MessageRole.HUMAN,
                                Audience.USER,
                                1,
                                "Synthetic order arrived damaged.",
                                "1".repeat(64))),
                dossier,
                Instant.parse("2026-07-20T08:01:00Z"));
    }

    private static EventRequest eventRequest() {
        return new EventRequest(
                "EVENT_P4_USER_2",
                "MESSAGE_P4_USER_2",
                IntakeTestFixtures.binding(),
                2,
                5,
                Audience.USER,
                SourceType.ROOM_MESSAGE,
                "The package photo is referenced by MESSAGE_P4_USER_2.",
                List.of("MESSAGE_P4_USER_2"),
                Instant.parse("2026-07-20T08:02:00Z"),
                Instant.parse("2026-07-20T08:02:01Z"));
    }

    private static com.fasterxml.jackson.databind.JsonNode fixture(String name) throws Exception {
        return MAPPER.readTree(
                Path.of(
                                "..",
                                "contracts",
                                "agent-platform",
                                "intake",
                                "v2",
                                "fixtures",
                                "valid",
                                name)
                        .toFile());
    }

    private static final class CapturingPublisher implements IntakeImmutablePayloadPublisher {
        private PublishRequest last;
        private int calls;

        @Override
        public StoredPayload publish(PublishRequest request) {
            last = request;
            calls++;
            return new StoredPayload(
                    request.artifactId(),
                    request.schemaVersion(),
                    "urn:intake:payload:" + request.artifactId(),
                    "version-1",
                    request.contentSha256(),
                    request.canonicalPayload().length);
        }
    }
}

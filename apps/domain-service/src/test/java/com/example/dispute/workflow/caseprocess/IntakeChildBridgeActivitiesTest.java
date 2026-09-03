package com.example.dispute.workflow.caseprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.domain.IntakeChildBridgeActivitiesAdapter;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.CommandSource;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.DomainEventSource;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.ReadUnavailableException;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.StartSource;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.ActiveChildBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.DomainEventRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.StartRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import io.temporal.failure.ApplicationFailure;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

class IntakeChildBridgeActivitiesTest {

    private static final String TENANT = "tenant-bridge";
    private static final String CASE_ID = "CASE_BRIDGE";
    private static final String ROOM_ID = "ROOM_BRIDGE";
    private static final String EPOCH_ID = "EPOCH_BRIDGE";
    private static final long EPOCH = 3;
    private static final long FENCE = 7;
    private static final long PROCESS_REVISION = 11;
    private static final long ROOM_REVISION = 13;
    private static final long COMMAND_SEQUENCE = 17;
    private static final long EVENT_SEQUENCE = 19;
    private static final String PAYLOAD_HASH = "a".repeat(64);
    private static final String REQUEST_HASH = "b".repeat(64);
    private static final String RESULT_HASH = "c".repeat(64);
    private static final String INITIATOR_SCOPE = "d".repeat(64);
    private static final String RESPONDENT_SCOPE = "e".repeat(64);
    private static final String EVENT_HASH = "f".repeat(64);
    private static final String OPERATION_KEY = "intake.operation:" + CASE_ID + ":CMD_BRIDGE";

    private FakeReadPort port;
    private IntakeChildBridgeActivitiesAdapter adapter;

    @BeforeEach
    void setUp() {
        port = new FakeReadPort();
        adapter = new IntakeChildBridgeActivitiesAdapter(port);
    }

    @Test
    void mapsOnlyReadSideEnrichmentToTheTypedIntakeProtocol() {
        var startBinding = adapter.bindStart(startRequest(WriterMode.SHADOW));
        assertThat(startBinding.activeBinding()).isEqualTo(activeBinding());
        assertThat(startBinding.start().tenantSurrogate()).isEqualTo(TENANT);
        assertThat(startBinding.start().roomEpoch()).isEqualTo(EPOCH);
        assertThat(startBinding.start().fencingToken()).isEqualTo(FENCE);
        assertThat(startBinding.start().workflowBuildId()).isEqualTo("intake-room-build.v1");
        assertThat(startBinding.start().initiatorActorScopeHash()).isEqualTo(INITIATOR_SCOPE);

        var commandBinding = adapter.bindCommand(commandRequest());
        assertThat(commandBinding.sourcePayloadHash()).isEqualTo(PAYLOAD_HASH);
        assertThat(commandBinding.processRevision()).isEqualTo(PROCESS_REVISION);
        assertThat(commandBinding.roomRevision()).isEqualTo(ROOM_REVISION);
        assertThat(commandBinding.command().commandType()).isEqualTo(IntakeCommandType.INTAKE_CANCEL);
        assertThat(commandBinding.command().party()).isEqualTo(IntakeParty.INITIATOR);
        assertThat(commandBinding.command().actorScopeHash()).isEqualTo(INITIATOR_SCOPE);

        var eventBinding = adapter.bindDomainEvent(eventRequest("INTAKE_CANCELLED"));
        assertThat(eventBinding.sourcePayloadHash()).isEqualTo(PAYLOAD_HASH);
        assertThat(eventBinding.requestHash()).isEqualTo(REQUEST_HASH);
        assertThat(eventBinding.event().eventType()).isEqualTo(IntakeDomainEventType.CANCELLED);
        assertThat(eventBinding.event().eventHash()).isEqualTo(EVENT_HASH);
        assertThat(eventBinding.event().requestHash()).isEqualTo(REQUEST_HASH);
    }

    @Test
    void merchantInitiatedCommandUsesTheAuthoritativeTypedParty() {
        port.command = commandSource(
                "CMD_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE, COMMAND_SEQUENCE,
                CommandType.INTAKE_CANCEL, PAYLOAD_HASH, REQUEST_HASH,
                PROCESS_REVISION, ROOM_REVISION, IntakeParty.INITIATOR);

        var binding = adapter.bindCommand(
                commandRequest(CommandType.INTAKE_CANCEL, ActorRole.MERCHANT));

        assertThat(binding.command().party()).isEqualTo(IntakeParty.INITIATOR);
        assertThat(binding.command().actorScopeHash()).isEqualTo(INITIATOR_SCOPE);
    }

    @Test
    void keepsCaseAndRoomWorkflowBindingsSeparateAndRejectsTemporalMode() {
        var binding = adapter.bindStart(startRequest(WriterMode.SHADOW));
        assertThat(binding.activeBinding().caseWorkflowType())
                .isEqualTo(CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE);
        assertThat(binding.activeBinding().caseWorkflowBuildId())
                .isEqualTo("case-workflow-build.v1");
        assertThat(binding.activeBinding().roomWorkflowType()).isEqualTo("IntakeRoomWorkflow");
        assertThat(binding.start().workflowBuildId()).isEqualTo("intake-room-build.v1");

        assertInvariant(
                () -> adapter.bindStart(startRequest(WriterMode.TEMPORAL)),
                "current typed Intake bridge gate requires SHADOW");
    }

    @Test
    void rejectsMissingOrConflictingPersistedStartBindings() {
        port.start = new StartSource(
                activeBinding(FENCE + 1),
                provisioning(WriterMode.SHADOW).payloadSha256(),
                "intake-prompt.v2",
                "intake-model.synthetic.v1",
                "intake-turn-proposal.v2",
                "intake-policy.v2",
                "intake-guardrail.v2",
                "no-tools.v1",
                INITIATOR_SCOPE,
                RESPONDENT_SCOPE);
        assertInvariant(() -> adapter.bindStart(startRequest(WriterMode.SHADOW)));

        port.start = startSource("0".repeat(64));
        assertInvariant(() -> adapter.bindStart(startRequest(WriterMode.SHADOW)));

        port.start = null;
        assertInvariant(() -> adapter.bindStart(startRequest(WriterMode.SHADOW)));
    }

    @Test
    void rejectsProvisionedChildWorkflowTypeOrBuildDivergence() {
        ProvisionRoomEpoch provision = provisioning(WriterMode.SHADOW);

        ActiveChildBinding genericChild = activeBinding(
                "room-epoch-selection.v2",
                CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                "RoomControlWorkflow",
                "intake-room-build.v1");
        port.start = startSource(genericChild, provision.payloadSha256());
        assertInvariant(
                () -> adapter.bindStart(startRequest(provision, genericChild)),
                "room workflow type mismatch");

        ActiveChildBinding otherBuild = activeBinding(
                "room-epoch-selection.v2",
                CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                "IntakeRoomWorkflow",
                "intake-room-build.v2");
        port.start = startSource(otherBuild, provision.payloadSha256());
        assertInvariant(
                () -> adapter.bindStart(startRequest(provision, otherBuild)),
                "room workflow build id mismatch");
    }

    @Test
    void rejectsNonV2OrGenericWorkflowSelectionsForCommandsAndEvents() {
        List.of(
                        activeBinding(
                                "room-epoch-selection.v1",
                                CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                                "IntakeRoomWorkflow",
                                "intake-room-build.v1"),
                        activeBinding(
                                "room-epoch-selection.v999",
                                CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                                "IntakeRoomWorkflow",
                                "intake-room-build.v1"),
                        activeBinding(
                                "room-epoch-selection.v2",
                                "RoomControlWorkflow",
                                "IntakeRoomWorkflow",
                                "intake-room-build.v1"),
                        activeBinding(
                                "room-epoch-selection.v2",
                                CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                                "RoomControlWorkflow",
                                "intake-room-build.v1"))
                .forEach(this::assertCommandAndEventSelectionInvariant);
    }

    @Test
    void rejectsReturnedCommandTupleIdTypeEpochFenceSequenceHashesAndRevisions() {
        assertCommandInvariant(commandSource("OTHER", TENANT, CASE_ID, EPOCH, FENCE,
                COMMAND_SEQUENCE, CommandType.INTAKE_CANCEL, PAYLOAD_HASH, REQUEST_HASH,
                PROCESS_REVISION, ROOM_REVISION, IntakeParty.INITIATOR));
        assertCommandInvariant(commandSource("CMD_BRIDGE", "other-tenant", CASE_ID, EPOCH, FENCE,
                COMMAND_SEQUENCE, CommandType.INTAKE_CANCEL, PAYLOAD_HASH, REQUEST_HASH,
                PROCESS_REVISION, ROOM_REVISION, IntakeParty.INITIATOR));
        assertCommandInvariant(commandSource("CMD_BRIDGE", TENANT, CASE_ID, EPOCH + 1, FENCE,
                COMMAND_SEQUENCE, CommandType.INTAKE_CANCEL, PAYLOAD_HASH, REQUEST_HASH,
                PROCESS_REVISION, ROOM_REVISION, IntakeParty.INITIATOR));
        assertCommandInvariant(commandSource("CMD_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE + 1,
                COMMAND_SEQUENCE, CommandType.INTAKE_CANCEL, PAYLOAD_HASH, REQUEST_HASH,
                PROCESS_REVISION, ROOM_REVISION, IntakeParty.INITIATOR));
        assertCommandInvariant(commandSource("CMD_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE,
                COMMAND_SEQUENCE + 1, CommandType.INTAKE_CANCEL, PAYLOAD_HASH, REQUEST_HASH,
                PROCESS_REVISION, ROOM_REVISION, IntakeParty.INITIATOR));
        assertCommandInvariant(commandSource("CMD_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE,
                COMMAND_SEQUENCE, CommandType.INTAKE_CONFIRM, PAYLOAD_HASH, REQUEST_HASH,
                PROCESS_REVISION, ROOM_REVISION, IntakeParty.INITIATOR));
        assertCommandInvariant(commandSource("CMD_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE,
                COMMAND_SEQUENCE, CommandType.INTAKE_CANCEL, "0".repeat(64), REQUEST_HASH,
                PROCESS_REVISION, ROOM_REVISION, IntakeParty.INITIATOR));
        assertCommandInvariant(commandSource("CMD_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE,
                COMMAND_SEQUENCE, CommandType.INTAKE_CANCEL, PAYLOAD_HASH, "0".repeat(64),
                PROCESS_REVISION, ROOM_REVISION, IntakeParty.INITIATOR));
        assertCommandInvariant(commandSource("CMD_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE,
                COMMAND_SEQUENCE, CommandType.INTAKE_CANCEL, PAYLOAD_HASH, REQUEST_HASH,
                PROCESS_REVISION + 1, ROOM_REVISION, IntakeParty.INITIATOR));
        assertCommandInvariant(commandSource("CMD_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE,
                COMMAND_SEQUENCE, CommandType.INTAKE_CANCEL, PAYLOAD_HASH, REQUEST_HASH,
                PROCESS_REVISION, -1, IntakeParty.INITIATOR));
    }

    @Test
    void rejectsUnknownCommandAndActorTypes() {
        port.command = commandSource("CMD_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE,
                COMMAND_SEQUENCE, CommandType.CASE_OPEN, PAYLOAD_HASH, REQUEST_HASH,
                PROCESS_REVISION, ROOM_REVISION, IntakeParty.INITIATOR);
        assertInvariant(() -> adapter.bindCommand(commandRequest(CommandType.CASE_OPEN, ActorRole.USER)));

        port.command = commandSource("CMD_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE,
                COMMAND_SEQUENCE, CommandType.INTAKE_CANCEL, PAYLOAD_HASH, REQUEST_HASH,
                PROCESS_REVISION, ROOM_REVISION, IntakeParty.INITIATOR);
        assertInvariant(
                () -> adapter.bindCommand(commandRequest(CommandType.INTAKE_CANCEL, ActorRole.SYSTEM)));
    }

    @Test
    void explicitlyMapsActualIntakeDomainEventNames() {
        port.event = eventSource(
                "INITIATOR_INTAKE_COMPLETED", IntakeDomainEventType.INITIATOR_ACCEPTED,
                "EVT_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE, EVENT_SEQUENCE,
                PAYLOAD_HASH, PROCESS_REVISION, ROOM_REVISION);
        var binding = adapter.bindDomainEvent(eventRequest("INITIATOR_INTAKE_COMPLETED"));
        assertThat(binding.event().eventType()).isEqualTo(IntakeDomainEventType.INITIATOR_ACCEPTED);

        port.event = eventSource(
                "RESPONDENT_INTAKE_COMPLETED", IntakeDomainEventType.RESPONDENT_CONFIRMED,
                "EVT_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE, EVENT_SEQUENCE,
                PAYLOAD_HASH, PROCESS_REVISION, ROOM_REVISION,
                IntakeParty.RESPONDENT, RESPONDENT_SCOPE);
        var respondent = adapter.bindDomainEvent(eventRequest("RESPONDENT_INTAKE_COMPLETED"));
        assertThat(respondent.event().eventType()).isEqualTo(IntakeDomainEventType.RESPONDENT_CONFIRMED);
    }

    @Test
    void rejectsReturnedEventTupleIdTypeEpochFenceSequenceHashAndRevisions() {
        assertEventInvariant(eventSource("INTAKE_CANCELLED", IntakeDomainEventType.CANCELLED,
                "OTHER", TENANT, CASE_ID, EPOCH, FENCE, EVENT_SEQUENCE,
                PAYLOAD_HASH, PROCESS_REVISION, ROOM_REVISION));
        assertEventInvariant(eventSource("INTAKE_CANCELLED", IntakeDomainEventType.CANCELLED,
                "EVT_BRIDGE", "other-tenant", CASE_ID, EPOCH, FENCE, EVENT_SEQUENCE,
                PAYLOAD_HASH, PROCESS_REVISION, ROOM_REVISION));
        assertEventInvariant(eventSource("INTAKE_CANCELLED", IntakeDomainEventType.CANCELLED,
                "EVT_BRIDGE", TENANT, CASE_ID, EPOCH + 1, FENCE, EVENT_SEQUENCE,
                PAYLOAD_HASH, PROCESS_REVISION, ROOM_REVISION));
        assertEventInvariant(eventSource("INTAKE_CANCELLED", IntakeDomainEventType.CANCELLED,
                "EVT_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE + 1, EVENT_SEQUENCE,
                PAYLOAD_HASH, PROCESS_REVISION, ROOM_REVISION));
        assertEventInvariant(eventSource("INTAKE_CANCELLED", IntakeDomainEventType.CANCELLED,
                "EVT_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE, EVENT_SEQUENCE + 1,
                PAYLOAD_HASH, PROCESS_REVISION, ROOM_REVISION));
        assertEventInvariant(eventSource("INTAKE_CANCELLED", IntakeDomainEventType.NOT_ADMISSIBLE,
                "EVT_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE, EVENT_SEQUENCE,
                PAYLOAD_HASH, PROCESS_REVISION, ROOM_REVISION));
        assertEventInvariant(eventSource("INTAKE_CANCELLED", IntakeDomainEventType.CANCELLED,
                "EVT_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE, EVENT_SEQUENCE,
                "0".repeat(64), PROCESS_REVISION, ROOM_REVISION));
        assertEventInvariant(eventSource("INTAKE_CANCELLED", IntakeDomainEventType.CANCELLED,
                "EVT_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE, EVENT_SEQUENCE,
                PAYLOAD_HASH, -1, ROOM_REVISION));
        assertEventInvariant(eventSource("INTAKE_CANCELLED", IntakeDomainEventType.CANCELLED,
                "EVT_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE, EVENT_SEQUENCE,
                PAYLOAD_HASH, PROCESS_REVISION, -1));
    }

    @Test
    void rejectsMissingAndUnknownDomainEvents() {
        port.event = eventSource(
                "INTAKE_UNKNOWN", IntakeDomainEventType.CANCELLED,
                "EVT_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE, EVENT_SEQUENCE,
                PAYLOAD_HASH, PROCESS_REVISION, ROOM_REVISION);
        assertInvariant(() -> adapter.bindDomainEvent(eventRequest("INTAKE_UNKNOWN")));

        port.event = null;
        assertInvariant(() -> adapter.bindDomainEvent(eventRequest("INTAKE_CANCELLED")));
    }

    @Test
    void retriesOnlyExplicitReadPortUnavailability() {
        port.failure = new ReadUnavailableException("temporary read outage");
        assertThatThrownBy(() -> adapter.bindStart(startRequest(WriterMode.SHADOW)))
                .isInstanceOfSatisfying(ApplicationFailure.class, failure -> {
                    assertThat(failure.getType())
                            .isEqualTo(IntakeChildBridgeActivitiesAdapter.READ_UNAVAILABLE);
                    assertThat(failure.isNonRetryable()).isFalse();
                });

        port.failure = new IllegalStateException("unknown read failure");
        assertThatThrownBy(() -> adapter.bindCommand(commandRequest()))
                .isInstanceOfSatisfying(ApplicationFailure.class, failure -> {
                    assertThat(failure.getType())
                            .isEqualTo(IntakeChildBridgeActivitiesAdapter.UNCLASSIFIED_READ_FAILURE);
                    assertThat(failure.isNonRetryable()).isTrue();
                });
    }

    @Test
    void requestContractsExposeOnlyTheGenericReferenceAndActiveBinding() {
        assertRecordComponents(
                StartRequest.class,
                Map.of(
                        "schemaVersion", String.class,
                        "provisioning", ProvisionRoomEpoch.class,
                        "activeBinding", ActiveChildBinding.class));
        assertRecordComponents(
                CommandRequest.class,
                Map.of(
                        "schemaVersion", String.class,
                        "command", CaseCommandRef.class,
                        "activeBinding", ActiveChildBinding.class));
        assertRecordComponents(
                DomainEventRequest.class,
                Map.of(
                        "schemaVersion", String.class,
                        "event", CaseDomainEventRef.class,
                        "activeBinding", ActiveChildBinding.class));
    }

    @Test
    void adapterIsPlainAndDependsOnlyOnTheReadPort() {
        assertThat(AnnotatedElementUtils.hasAnnotation(
                        IntakeChildBridgeActivitiesAdapter.class, Component.class))
                .isFalse();
        assertThat(Arrays.stream(IntakeChildBridgeActivitiesAdapter.class.getAnnotations())
                        .map(annotation -> annotation.annotationType().getPackageName()))
                .noneMatch(packageName -> packageName.startsWith("org.springframework"));

        assertThat(Arrays.stream(IntakeChildBridgeActivitiesAdapter.class.getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(field.getModifiers()))
                        .map(field -> field.getType().getName()))
                .containsExactly(IntakeChildBridgeReadPort.class.getName(), boolean.class.getName());
        assertThat(IntakeChildBridgeActivitiesAdapter.class.getDeclaredFields())
                .extracting(field -> field.getType().getName())
                .containsOnly(
                        String.class.getName(),
                        IntakeChildBridgeReadPort.class.getName(),
                        boolean.class.getName());
        assertThat(IntakeChildBridgeActivitiesAdapter.class.getDeclaredConstructors()).hasSize(2);
    }

    private void assertCommandInvariant(CommandSource source) {
        port.command = source;
        assertInvariant(() -> adapter.bindCommand(commandRequest()));
    }

    private void assertEventInvariant(DomainEventSource source) {
        port.event = source;
        assertInvariant(() -> adapter.bindDomainEvent(eventRequest("INTAKE_CANCELLED")));
    }

    private void assertCommandAndEventSelectionInvariant(ActiveChildBinding binding) {
        port.command = commandSource(
                binding,
                "CMD_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE, COMMAND_SEQUENCE,
                CommandType.INTAKE_CANCEL, PAYLOAD_HASH, REQUEST_HASH,
                PROCESS_REVISION, ROOM_REVISION, IntakeParty.INITIATOR);
        assertInvariant(() -> adapter.bindCommand(
                commandRequest(CommandType.INTAKE_CANCEL, ActorRole.USER, binding)));

        port.event = eventSource(
                binding,
                "INTAKE_CANCELLED", IntakeDomainEventType.CANCELLED,
                "EVT_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE, EVENT_SEQUENCE,
                PAYLOAD_HASH, PROCESS_REVISION, ROOM_REVISION,
                IntakeParty.INITIATOR, INITIATOR_SCOPE);
        assertInvariant(() -> adapter.bindDomainEvent(eventRequest("INTAKE_CANCELLED", binding)));
    }

    private static void assertRecordComponents(Class<?> recordType, Map<String, Class<?>> expected) {
        Map<String, Class<?>> actual = Arrays.stream(recordType.getRecordComponents())
                .collect(Collectors.toMap(
                        component -> component.getName(), component -> component.getType()));
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    private static void assertInvariant(Runnable invocation) {
        assertInvariant(invocation, null);
    }

    private static void assertInvariant(Runnable invocation, String expectedCauseMessage) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ApplicationFailure.class, failure -> {
                    assertThat(failure.getType())
                            .isEqualTo(IntakeChildBridgeActivitiesAdapter.INVARIANT_FAILURE);
                    assertThat(failure.isNonRetryable()).isTrue();
                    if (expectedCauseMessage != null) {
                        assertThat(failure.getCause()).hasMessage(expectedCauseMessage);
                    }
                });
    }

    private static StartRequest startRequest(WriterMode writerMode) {
        return new StartRequest(
                "intake-child-start-request.v1", provisioning(writerMode), activeBinding());
    }

    private static StartRequest startRequest(
            ProvisionRoomEpoch provision, ActiveChildBinding binding) {
        return new StartRequest("intake-child-start-request.v1", provision, binding);
    }

    private static CommandRequest commandRequest() {
        return commandRequest(CommandType.INTAKE_CANCEL, ActorRole.USER);
    }

    private static CommandRequest commandRequest(CommandType type, ActorRole role) {
        return commandRequest(type, role, activeBinding());
    }

    private static CommandRequest commandRequest(
            CommandType type, ActorRole role, ActiveChildBinding binding) {
        return new CommandRequest(
                "intake-child-command-request.v1", command(type, role), binding);
    }

    private static DomainEventRequest eventRequest(String sourceEventType) {
        return eventRequest(sourceEventType, activeBinding());
    }

    private static DomainEventRequest eventRequest(
            String sourceEventType, ActiveChildBinding binding) {
        return new DomainEventRequest(
                "intake-child-domain-event-request.v1",
                event(sourceEventType),
                binding);
    }

    private static ActiveChildBinding activeBinding() {
        return activeBinding(FENCE);
    }

    private static ActiveChildBinding activeBinding(long fence) {
        return activeBinding(
                fence,
                "room-epoch-selection.v2",
                CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                "IntakeRoomWorkflow",
                "intake-room-build.v1");
    }

    private static ActiveChildBinding activeBinding(
            String selectionSchemaVersion,
            String caseWorkflowType,
            String roomWorkflowType,
            String roomWorkflowBuildId) {
        return activeBinding(
                FENCE,
                selectionSchemaVersion,
                caseWorkflowType,
                roomWorkflowType,
                roomWorkflowBuildId);
    }

    private static ActiveChildBinding activeBinding(
            long fence,
            String selectionSchemaVersion,
            String caseWorkflowType,
            String roomWorkflowType,
            String roomWorkflowBuildId) {
        return new ActiveChildBinding(
                "active-intake-child-binding.v1",
                TENANT,
                CASE_ID,
                EPOCH,
                fence,
                selectionSchemaVersion,
                caseWorkflowType,
                "case-workflow-build.v1",
                roomWorkflowType,
                roomWorkflowBuildId);
    }

    private static StartSource startSource(String provisioningHash) {
        return startSource(activeBinding(), provisioningHash);
    }

    private static StartSource startSource(
            ActiveChildBinding binding, String provisioningHash) {
        return new StartSource(
                binding,
                provisioningHash,
                "intake-prompt.v2",
                "intake-model.synthetic.v1",
                "intake-turn-proposal.v2",
                "intake-policy.v2",
                "intake-guardrail.v2",
                "no-tools.v1",
                INITIATOR_SCOPE,
                RESPONDENT_SCOPE);
    }

    private static CommandSource commandSource(
            String commandId,
            String tenant,
            String caseId,
            long epoch,
            long fence,
            long sequence,
            CommandType type,
            String payloadHash,
            String requestHash,
            long processRevision,
            long roomRevision,
            IntakeParty party) {
        return commandSource(
                activeBinding(), commandId, tenant, caseId, epoch, fence, sequence, type,
                payloadHash, requestHash, processRevision, roomRevision, party);
    }

    private static CommandSource commandSource(
            ActiveChildBinding binding,
            String commandId,
            String tenant,
            String caseId,
            long epoch,
            long fence,
            long sequence,
            CommandType type,
            String payloadHash,
            String requestHash,
            long processRevision,
            long roomRevision,
            IntakeParty party) {
        String scope = party == IntakeParty.RESPONDENT ? RESPONDENT_SCOPE : INITIATOR_SCOPE;
        return new CommandSource(
                binding,
                commandId,
                tenant,
                caseId,
                epoch,
                fence,
                sequence,
                type,
                payloadHash,
                requestHash,
                processRevision,
                roomRevision,
                party,
                scope,
                OPERATION_KEY,
                null);
    }

    private static DomainEventSource eventSource(
            String sourceEventType,
            IntakeDomainEventType type,
            String eventId,
            String tenant,
            String caseId,
            long epoch,
            long fence,
            long sequence,
            String payloadHash,
            long processRevision,
            long roomRevision) {
        return eventSource(
                sourceEventType, type, eventId, tenant, caseId, epoch, fence, sequence,
                payloadHash, processRevision, roomRevision, IntakeParty.INITIATOR, INITIATOR_SCOPE);
    }

    private static DomainEventSource eventSource(
            String sourceEventType,
            IntakeDomainEventType type,
            String eventId,
            String tenant,
            String caseId,
            long epoch,
            long fence,
            long sequence,
            String payloadHash,
            long processRevision,
            long roomRevision,
            IntakeParty party,
            String actorScopeHash) {
        return eventSource(
                activeBinding(), sourceEventType, type, eventId, tenant, caseId, epoch, fence,
                sequence, payloadHash, processRevision, roomRevision, party, actorScopeHash);
    }

    private static DomainEventSource eventSource(
            ActiveChildBinding binding,
            String sourceEventType,
            IntakeDomainEventType type,
            String eventId,
            String tenant,
            String caseId,
            long epoch,
            long fence,
            long sequence,
            String payloadHash,
            long processRevision,
            long roomRevision,
            IntakeParty party,
            String actorScopeHash) {
        return new DomainEventSource(
                binding,
                eventId,
                sourceEventType,
                type,
                tenant,
                caseId,
                epoch,
                fence,
                sequence,
                payloadHash,
                "urn:after-sale-flow:intake-event:EVT_BRIDGE",
                EVENT_HASH,
                party,
                "CMD_BRIDGE",
                actorScopeHash,
                OPERATION_KEY,
                REQUEST_HASH,
                RESULT_HASH,
                processRevision,
                roomRevision,
                null,
                null);
    }

    private static ProvisionRoomEpoch provisioning(WriterMode writerMode) {
        Object[] legacyArguments = new Object[] {
                ProvisionRoomEpoch.SCHEMA_VERSION,
                EPOCH_ID,
                TENANT,
                CASE_ID,
                ROOM_ID,
                RoomType.INTAKE,
                EPOCH,
                PROCESS_REVISION,
                ROOM_REVISION,
                FENCE,
                "INTAKE",
                "INTAKE",
                "OPEN",
                writerMode,
                CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID),
                CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.INTAKE, EPOCH),
                "room-epoch-selection.v2",
                "case-process.v1",
                CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                "case-workflow-build.v1",
                "intake.v2",
                "2.0.0",
                "intake-checkpoint.v2",
                "agent-stream.v2",
                COMMAND_SEQUENCE - 1,
                EVENT_SEQUENCE - 1,
                COMMAND_SEQUENCE,
                EVENT_SEQUENCE,
                null,
                null,
                null,
                Instant.parse("2026-07-21T00:00:00Z")
        };
        try {
            return new ProvisionRoomEpoch(
                    (String) legacyArguments[0],
                    (String) legacyArguments[1],
                    (String) legacyArguments[2],
                    (String) legacyArguments[3],
                    (String) legacyArguments[4],
                    (RoomType) legacyArguments[5],
                    (long) legacyArguments[6],
                    (long) legacyArguments[7],
                    (long) legacyArguments[8],
                    (long) legacyArguments[9],
                    (String) legacyArguments[10],
                    (String) legacyArguments[11],
                    (String) legacyArguments[12],
                    (WriterMode) legacyArguments[13],
                    (String) legacyArguments[14],
                    (String) legacyArguments[15],
                    (String) legacyArguments[16],
                    (String) legacyArguments[17],
                    (String) legacyArguments[18],
                    (String) legacyArguments[19],
                    (String) legacyArguments[20],
                    (String) legacyArguments[21],
                    (String) legacyArguments[22],
                    (String) legacyArguments[23],
                    (long) legacyArguments[24],
                    (long) legacyArguments[25],
                    (long) legacyArguments[26],
                    (long) legacyArguments[27],
                    (Instant) legacyArguments[28],
                    (String) legacyArguments[29],
                    (String) legacyArguments[30],
                    (Instant) legacyArguments[31]);
        } catch (IllegalArgumentException v2RequiresRoomBinding) {
            return provisioningWithV2RoomBinding(legacyArguments, v2RequiresRoomBinding);
        }
    }

    private static ProvisionRoomEpoch provisioningWithV2RoomBinding(
            Object[] legacyArguments, IllegalArgumentException originalFailure) {
        var components = ProvisionRoomEpoch.class.getRecordComponents();
        if (components.length != legacyArguments.length + 2) {
            throw originalFailure;
        }
        Object[] v2Arguments = new Object[components.length];
        System.arraycopy(legacyArguments, 0, v2Arguments, 0, 20);
        v2Arguments[20] = "IntakeRoomWorkflow";
        v2Arguments[21] = "intake-room-build.v1";
        System.arraycopy(
                legacyArguments,
                20,
                v2Arguments,
                22,
                legacyArguments.length - 20);
        Class<?>[] parameterTypes = new Class<?>[components.length];
        for (int index = 0; index < components.length; index++) {
            parameterTypes[index] = components[index].getType();
        }
        try {
            return ProvisionRoomEpoch.class
                    .getDeclaredConstructor(parameterTypes)
                    .newInstance(v2Arguments);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError("cannot construct the v2 provisioning fixture", reflectionFailure);
        }
    }

    private static CaseCommandRef command(CommandType type, ActorRole role) {
        return new CaseCommandRef(
                "case-command-ref.v1",
                "CMD_BRIDGE",
                TENANT,
                CASE_ID,
                COMMAND_SEQUENCE,
                type,
                RoomType.INTAKE,
                EPOCH,
                new ActorRef("actor-bridge", role, List.of("case:command")),
                new PayloadRef(
                        "intake-command.v1",
                        "urn:after-sale-flow:intake-command:CMD_BRIDGE",
                        PAYLOAD_HASH,
                        32),
                PROCESS_REVISION,
                Instant.parse("2026-07-21T00:00:02Z"),
                Instant.parse("2026-07-21T00:05:02Z"),
                "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01",
                REQUEST_HASH);
    }

    private static CaseDomainEventRef event(String eventType) {
        return new CaseDomainEventRef(
                "case-domain-event-ref.v1",
                "EVT_BRIDGE",
                TENANT,
                CASE_ID,
                EVENT_SEQUENCE,
                eventType,
                RoomType.INTAKE,
                EPOCH,
                new PayloadRef(
                        "case-timeline-event.v1",
                        "urn:case-timeline-event:EVT_BRIDGE",
                        PAYLOAD_HASH,
                        64),
                Instant.parse("2026-07-21T00:00:03Z"),
                "00-" + "3".repeat(32) + "-" + "4".repeat(16) + "-01");
    }

    private static final class FakeReadPort implements IntakeChildBridgeReadPort {
        private StartSource start = startSource(provisioning(WriterMode.SHADOW).payloadSha256());
        private CommandSource command = commandSource(
                "CMD_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE, COMMAND_SEQUENCE,
                CommandType.INTAKE_CANCEL, PAYLOAD_HASH, REQUEST_HASH,
                PROCESS_REVISION, ROOM_REVISION, IntakeParty.INITIATOR);
        private DomainEventSource event = eventSource(
                "INTAKE_CANCELLED", IntakeDomainEventType.CANCELLED,
                "EVT_BRIDGE", TENANT, CASE_ID, EPOCH, FENCE, EVENT_SEQUENCE,
                PAYLOAD_HASH, PROCESS_REVISION, ROOM_REVISION);
        private RuntimeException failure;

        @Override
        public StartSource readStart(StartRequest request) {
            failIfArmed();
            if (start != null && request.provisioning().writerMode() == WriterMode.TEMPORAL) {
                return startSource(request.provisioning().payloadSha256());
            }
            return start;
        }

        @Override
        public CommandSource readCommand(CommandRequest request) {
            failIfArmed();
            return command;
        }

        @Override
        public DomainEventSource readDomainEvent(DomainEventRequest request) {
            failIfArmed();
            return event;
        }

        private void failIfArmed() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}

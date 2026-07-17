package com.example.dispute.workflow.temporal.caseprocess;

import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE;
import static io.temporal.api.enums.v1.ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceStream;
import com.example.dispute.workflow.temporal.room.common.RoomControlStart;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowQueue;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

public class CaseProcessWorkflowImpl implements CaseProcessWorkflow {

    private static final String CARRY_STATE_MEMO_KEY = "case_process_carry_state_v1";
    private static final int INBOX_CAPACITY = 128;
    private static final int LOAD_BATCH_SIZE = 64;
    private static final int MAX_GAP_RECOVERY_ATTEMPTS = 3;
    private static final long HISTORY_EVENT_LIMIT = 2000;
    private static final Duration RUN_MAX_AGE = Duration.ofHours(24);
    private static final Duration GAP_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TRACEPARENT =
            Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    private final CaseProcessLedgerActivities ledgerActivities =
            Workflow.newActivityStub(
                    CaseProcessLedgerActivities.class,
                    ActivityOptions.newBuilder()
                            .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
                            .setStartToCloseTimeout(Duration.ofSeconds(10))
                            .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setInitialInterval(Duration.ofSeconds(1))
                                            .setMaximumInterval(Duration.ofSeconds(5))
                                            .setMaximumAttempts(3)
                                            .build())
                            .build());
    private final WorkflowQueue<PendingCommand> commandInbox =
            Workflow.newQueue(INBOX_CAPACITY);
    private final WorkflowQueue<CaseDomainEventRef> eventInbox =
            Workflow.newQueue(INBOX_CAPACITY);
    private final NavigableMap<Long, PendingCommand> orderedCommands = new TreeMap<>();
    private final ArrayDeque<PendingCommand> replayChecks = new ArrayDeque<>();
    private final NavigableMap<Long, CaseDomainEventRef> bufferedEvents = new TreeMap<>();
    private final LinkedHashMap<String, ProcessedCommandIdentity> recentCommands =
            new LinkedHashMap<>();

    private String tenantSurrogate;
    private String caseId;
    private com.example.dispute.workflow.contract.v1.ContractTypes.RoomType activeRoomType;
    private long activeRoomEpoch = -1;
    private String activeChildWorkflowId;
    private RoomControlWorkflow activeRoomChild;
    private long observedProcessRevision;
    private long nextCommandSequence = 1;
    private long nextCaseEventSequence = 1;
    private long processedCommandCount;
    private long processedEventCount;
    private long highestObservedCommandSequence;
    private long highestObservedEventSequence;
    private int runGeneration;
    private int commandInboxCount;
    private int eventInboxCount;
    private int commandRecoveryAttempts;
    private int eventRecoveryAttempts;
    private boolean commandManualRecoveryRequired;
    private boolean eventManualRecoveryRequired;
    private boolean eventRecoveryForced;
    private boolean retrySequenceGapRequested;
    private boolean continueAsNewRequested;
    private String protocolErrorCode;
    private Promise<Void> runMaxAgeTimer;

    @Override
    public void run(CaseProcessCarryState carryState) {
        restoreCarryState(carryState);
        runMaxAgeTimer = Workflow.newTimer(RUN_MAX_AGE);
        while (true) {
            drainCommandInbox();
            drainEventInbox();
            applyManualRecoveryRequest();

            if (processReplayCheck()) {
                continue;
            }
            if (processNextCommand()) {
                continue;
            }
            if (processNextEvent()) {
                continue;
            }
            if (recoverCommandGap()) {
                continue;
            }
            if (recoverEventGap()) {
                continue;
            }
            if (shouldContinueAsNew() && canContinueAsNew()) {
                continueAsNew();
                return;
            }
            Workflow.await(this::hasWork);
        }
    }

    @Override
    public void acceptCommand(CaseCommandRef command) {
        validateCommandEnvelope(command);
        CompletablePromise<Void> completion = Workflow.newPromise();
        PendingCommand pending = PendingCommand.live(command, completion);
        commandInbox.put(pending);
        commandInboxCount++;
        completion.get();
    }

    @Override
    public void validateAcceptCommand(CaseCommandRef command) {
        validateCommandEnvelope(command);
    }

    @Override
    public void domainEventCommitted(CaseDomainEventRef event) {
        String validationError = eventValidationError(event);
        if (validationError != null) {
            protocolErrorCode = validationError;
            return;
        }
        highestObservedEventSequence =
                Math.max(highestObservedEventSequence, event.caseEventSequence());
        if (event.caseEventSequence() < nextCaseEventSequence) {
            return;
        }
        if (!eventInbox.offer(event)) {
            eventRecoveryForced = true;
            protocolErrorCode = "CASE_PROCESS_EVENT_INBOX_FULL";
            return;
        }
        eventInboxCount++;
    }

    @Override
    public void retrySequenceGap() {
        retrySequenceGapRequested = true;
    }

    @Override
    public void requestContinueAsNew() {
        continueAsNewRequested = true;
    }

    @Override
    public CaseProcessSnapshot state() {
        List<String> recentCommandIds = new ArrayList<>(recentCommands.keySet());
        return new CaseProcessSnapshot(
                "case-process-snapshot.v1",
                Workflow.getInfo().getWorkflowId(),
                Workflow.getInfo().getRunId(),
                tenantSurrogate,
                caseId,
                "CONTROL_PLANE_SHADOW",
                activeRoomType,
                activeRoomEpoch,
                activeChildWorkflowId,
                observedProcessRevision,
                nextCommandSequence,
                nextCaseEventSequence,
                processedCommandCount,
                processedEventCount,
                commandInboxCount + orderedCommands.size() + replayChecks.size(),
                eventInboxCount + bufferedEvents.size(),
                recentCommands.size(),
                highestObservedCommandSequence,
                highestObservedEventSequence,
                runGeneration,
                blockedReason(),
                protocolErrorCode,
                recentCommandIds);
    }

    private void restoreCarryState(CaseProcessCarryState startCarryState) {
        CaseProcessCarryState carry = CaseProcessCarryState.initial();
        if (Workflow.getInfo().getContinuedExecutionRunId().isPresent()) {
            carry = startCarryState;
            if (carry == null) {
                carry =
                        (CaseProcessCarryState)
                                Workflow.getMemo(
                                        CARRY_STATE_MEMO_KEY,
                                        CaseProcessCarryState.class);
            }
            if (carry == null) {
                throw protocolFailure(
                        "CASE_PROCESS_CARRY_STATE_MISSING",
                        "continued workflow is missing carry state");
            }
        }
        tenantSurrogate = carry.tenantSurrogate();
        caseId = carry.caseId();
        activeRoomType = carry.activeRoomType();
        activeRoomEpoch = carry.activeRoomEpoch();
        activeChildWorkflowId = carry.activeChildWorkflowId();
        observedProcessRevision = carry.observedProcessRevision();
        nextCommandSequence = carry.nextCommandSequence();
        nextCaseEventSequence = carry.nextCaseEventSequence();
        processedCommandCount = carry.processedCommandCount();
        processedEventCount = carry.processedEventCount();
        highestObservedCommandSequence = Math.max(0, nextCommandSequence - 1);
        highestObservedEventSequence = carry.highestObservedEventSequence();
        runGeneration = carry.runGeneration();
        commandRecoveryAttempts = carry.commandRecoveryAttempts();
        eventRecoveryAttempts = carry.eventRecoveryAttempts();
        commandManualRecoveryRequired = carry.commandManualRecoveryRequired();
        eventManualRecoveryRequired = carry.eventManualRecoveryRequired();
        protocolErrorCode = carry.protocolErrorCode();
        carry.recentCommands()
                .forEach(identity -> recentCommands.put(identity.commandId(), identity));
        carry.bufferedEvents()
                .forEach(event -> bufferedEvents.put(event.caseEventSequence(), event));
        if (activeChildWorkflowId != null) {
            activeRoomChild =
                    Workflow.newExternalWorkflowStub(
                            RoomControlWorkflow.class, activeChildWorkflowId);
        }
        if (tenantSurrogate != null) {
            requireWorkflowIdentity(tenantSurrogate, caseId);
        }
    }

    private void drainCommandInbox() {
        while (commandInboxCount > 0) {
            PendingCommand pending = commandInbox.poll();
            if (pending == null) {
                return;
            }
            commandInboxCount--;
            CaseCommandRef command = pending.command();
            bindIdentity(command.tenantSurrogate(), command.caseId());
            highestObservedCommandSequence =
                    Math.max(
                            highestObservedCommandSequence,
                            command.caseCommandSequence());
            if (command.caseCommandSequence() < nextCommandSequence) {
                replayChecks.addLast(pending);
                continue;
            }
            mergePendingCommand(pending);
        }
    }

    private void drainEventInbox() {
        while (eventInboxCount > 0) {
            CaseDomainEventRef event = eventInbox.poll();
            if (event == null) {
                return;
            }
            eventInboxCount--;
            bindIdentity(event.tenantSurrogate(), event.caseId());
            highestObservedEventSequence =
                    Math.max(highestObservedEventSequence, event.caseEventSequence());
            if (event.caseEventSequence() < nextCaseEventSequence) {
                continue;
            }
            mergeBufferedEvent(event);
        }
    }

    private boolean processReplayCheck() {
        PendingCommand pending = replayChecks.peekFirst();
        if (pending == null || commandManualRecoveryRequired) {
            return false;
        }
        try {
            List<CaseCommandRef> stored =
                    ledgerActivities.loadCaseCommands(
                            range(
                                    pending.command().caseCommandSequence(),
                                    pending.command().caseCommandSequence()));
            if (stored == null || stored.size() != 1) {
                markManualRecovery(
                        SequenceStream.COMMAND,
                        pending.command().caseCommandSequence(),
                        "COMMAND_LEDGER_RESPONSE_INVALID");
                return true;
            }
            try {
                validateLoadedCommand(
                        stored.getFirst(),
                        pending.command().caseCommandSequence(),
                        pending.command().caseCommandSequence());
            } catch (RuntimeException invalidResponse) {
                markManualRecovery(
                        SequenceStream.COMMAND,
                        pending.command().caseCommandSequence(),
                        "COMMAND_LEDGER_RESPONSE_INVALID");
                return true;
            }
            if (sameCommand(stored.getFirst(), pending.command())) {
                replayChecks.removeFirst();
                pending.complete();
                commandRecoveryAttempts = 0;
                commandManualRecoveryRequired = false;
                clearRecoveryError(SequenceStream.COMMAND);
                return true;
            }
            replayChecks.removeFirst();
            pending.fail(
                    protocolFailure(
                            "CASE_PROCESS_COMMAND_REPLAY_CONFLICT",
                            "replayed command does not match the Java command ledger"));
            protocolErrorCode = "CASE_PROCESS_COMMAND_REPLAY_CONFLICT";
            commandRecoveryAttempts = 0;
            return true;
        } catch (ActivityFailure failure) {
            rethrowIfCanceled(failure);
            failGapRecovery(
                    SequenceStream.COMMAND,
                    pending.command().caseCommandSequence(),
                    "COMMAND_REPLAY_LEDGER_UNAVAILABLE");
            return true;
        }
    }

    private boolean processNextCommand() {
        PendingCommand pending = orderedCommands.remove(nextCommandSequence);
        if (pending == null) {
            return false;
        }
        try {
            CaseCommandRef command = pending.command();
            ensureRoomChild(command);
            activeRoomChild.commandAccepted(command);
            observedProcessRevision =
                    Math.max(observedProcessRevision, command.expectedProcessRevision());
            recentCommands.put(
                    command.commandId(),
                    new ProcessedCommandIdentity(
                            command.commandId(),
                            command.caseCommandSequence(),
                            command.requestHash()));
            trimRecentCommands();
            nextCommandSequence++;
            processedCommandCount++;
            commandRecoveryAttempts = 0;
            commandManualRecoveryRequired = false;
            clearRecoveryError(SequenceStream.COMMAND);
            pending.complete();
        } catch (RuntimeException exception) {
            orderedCommands.put(nextCommandSequence, pending);
            protocolErrorCode = "CASE_PROCESS_COMMAND_ROUTING_FAILED";
            if (exception instanceof ApplicationFailure applicationFailure) {
                pending.fail(applicationFailure);
            }
            throw exception;
        }
        return true;
    }

    private boolean processNextEvent() {
        CaseDomainEventRef event = bufferedEvents.remove(nextCaseEventSequence);
        if (event == null) {
            return false;
        }
        if (activeRoomChild != null
                && activeRoomType == event.roomType()
                && activeRoomEpoch == event.roomEpoch()) {
            activeRoomChild.domainEventCommitted(event);
        }
        nextCaseEventSequence++;
        processedEventCount++;
        eventRecoveryAttempts = 0;
        eventManualRecoveryRequired = false;
        if (nextCaseEventSequence > highestObservedEventSequence) {
            eventRecoveryForced = false;
        }
        clearRecoveryError(SequenceStream.DOMAIN_EVENT);
        return true;
    }

    private boolean recoverCommandGap() {
        if (commandManualRecoveryRequired
                || tenantSurrogate == null
                || !hasCommandGap()) {
            return false;
        }
        long toSequence =
                Math.min(
                        highestObservedCommandSequence,
                        nextCommandSequence + LOAD_BATCH_SIZE - 1L);
        try {
            List<CaseCommandRef> loaded =
                    ledgerActivities.loadCaseCommands(
                            range(nextCommandSequence, toSequence));
            boolean progress = mergeLoadedCommands(loaded, nextCommandSequence, toSequence);
            if (commandManualRecoveryRequired) {
                return true;
            } else if (progress) {
                commandRecoveryAttempts = 0;
            } else {
                failGapRecovery(
                        SequenceStream.COMMAND,
                        highestObservedCommandSequence,
                        "COMMAND_SEQUENCE_NOT_FOUND");
            }
            return true;
        } catch (ActivityFailure failure) {
            rethrowIfCanceled(failure);
            failGapRecovery(
                    SequenceStream.COMMAND,
                    highestObservedCommandSequence,
                    "COMMAND_LEDGER_UNAVAILABLE");
            return true;
        }
    }

    private boolean recoverEventGap() {
        if (eventManualRecoveryRequired
                || tenantSurrogate == null
                || !hasEventGap()) {
            return false;
        }
        long toSequence =
                Math.min(
                        highestObservedEventSequence,
                        nextCaseEventSequence + LOAD_BATCH_SIZE - 1L);
        try {
            List<CaseDomainEventRef> loaded =
                    ledgerActivities.loadDomainEvents(
                            range(nextCaseEventSequence, toSequence));
            boolean progress = mergeLoadedEvents(loaded, nextCaseEventSequence, toSequence);
            if (eventManualRecoveryRequired) {
                return true;
            } else if (progress) {
                eventRecoveryAttempts = 0;
            } else {
                failGapRecovery(
                        SequenceStream.DOMAIN_EVENT,
                        highestObservedEventSequence,
                        "DOMAIN_EVENT_SEQUENCE_NOT_FOUND");
            }
            return true;
        } catch (ActivityFailure failure) {
            rethrowIfCanceled(failure);
            failGapRecovery(
                    SequenceStream.DOMAIN_EVENT,
                    highestObservedEventSequence,
                    "DOMAIN_EVENT_LEDGER_UNAVAILABLE");
            return true;
        }
    }

    private boolean mergeLoadedCommands(
            List<CaseCommandRef> loaded, long fromSequence, long toSequence) {
        if (loaded == null || loaded.size() > toSequence - fromSequence + 1) {
            markManualRecovery(
                    SequenceStream.COMMAND,
                    toSequence,
                    "COMMAND_LEDGER_RESPONSE_INVALID");
            return false;
        }
        List<CaseCommandRef> ordered;
        try {
            ordered =
                    loaded.stream()
                            .sorted(Comparator.comparingLong(CaseCommandRef::caseCommandSequence))
                            .toList();
            long previousSequence = -1;
            for (CaseCommandRef command : ordered) {
                validateLoadedCommand(command, fromSequence, toSequence);
                if (command.caseCommandSequence() == previousSequence) {
                    throw new IllegalArgumentException(
                            "command ledger returned a duplicate sequence");
                }
                previousSequence = command.caseCommandSequence();
            }
        } catch (RuntimeException invalidResponse) {
            markManualRecovery(
                    SequenceStream.COMMAND,
                    toSequence,
                    "COMMAND_LEDGER_RESPONSE_INVALID");
            return false;
        }
        ordered.forEach(
                command -> {
                    highestObservedCommandSequence =
                            Math.max(
                                    highestObservedCommandSequence,
                                    command.caseCommandSequence());
                    mergePendingCommand(PendingCommand.recovered(command));
                });
        return orderedCommands.containsKey(nextCommandSequence);
    }

    private boolean mergeLoadedEvents(
            List<CaseDomainEventRef> loaded, long fromSequence, long toSequence) {
        if (loaded == null || loaded.size() > toSequence - fromSequence + 1) {
            markManualRecovery(
                    SequenceStream.DOMAIN_EVENT,
                    toSequence,
                    "DOMAIN_EVENT_LEDGER_RESPONSE_INVALID");
            return false;
        }
        List<CaseDomainEventRef> ordered;
        try {
            ordered =
                    loaded.stream()
                            .sorted(Comparator.comparingLong(CaseDomainEventRef::caseEventSequence))
                            .toList();
            long previousSequence = -1;
            for (CaseDomainEventRef event : ordered) {
                validateLoadedEvent(event, fromSequence, toSequence);
                if (event.caseEventSequence() == previousSequence) {
                    throw new IllegalArgumentException(
                            "domain event ledger returned a duplicate sequence");
                }
                previousSequence = event.caseEventSequence();
            }
        } catch (RuntimeException invalidResponse) {
            markManualRecovery(
                    SequenceStream.DOMAIN_EVENT,
                    toSequence,
                    "DOMAIN_EVENT_LEDGER_RESPONSE_INVALID");
            return false;
        }
        ordered.forEach(
                event -> {
                    highestObservedEventSequence =
                            Math.max(
                                    highestObservedEventSequence,
                                    event.caseEventSequence());
                    mergeBufferedEvent(event);
                });
        return bufferedEvents.containsKey(nextCaseEventSequence);
    }

    private void mergePendingCommand(PendingCommand incoming) {
        long sequence = incoming.command().caseCommandSequence();
        PendingCommand existing = orderedCommands.get(sequence);
        if (existing == null) {
            orderedCommands.put(sequence, incoming);
            return;
        }
        if (!sameCommand(existing.command(), incoming.command())) {
            incoming.fail(
                    protocolFailure(
                            "CASE_PROCESS_COMMAND_SEQUENCE_CONFLICT",
                            "one command sequence is bound to different commands"));
            protocolErrorCode = "CASE_PROCESS_COMMAND_SEQUENCE_CONFLICT";
            commandManualRecoveryRequired = true;
            return;
        }
        existing.absorb(incoming);
    }

    private void mergeBufferedEvent(CaseDomainEventRef incoming) {
        long sequence = incoming.caseEventSequence();
        CaseDomainEventRef existing = bufferedEvents.get(sequence);
        if (existing != null) {
            if (!sameEvent(existing, incoming)) {
                protocolErrorCode = "CASE_PROCESS_EVENT_SEQUENCE_CONFLICT";
                eventManualRecoveryRequired = true;
            }
            return;
        }
        if (bufferedEvents.size() >= CaseProcessCarryState.MAX_BUFFERED_EVENTS) {
            Map.Entry<Long, CaseDomainEventRef> last = bufferedEvents.lastEntry();
            if (last != null && sequence < last.getKey()) {
                bufferedEvents.pollLastEntry();
                bufferedEvents.put(sequence, incoming);
            } else {
                eventRecoveryForced = true;
            }
            return;
        }
        bufferedEvents.put(sequence, incoming);
    }

    private void ensureRoomChild(CaseCommandRef command) {
        String desiredChildId =
                CaseProcessWorkflowProtocol.roomWorkflowId(
                        command.caseId(), command.roomType(), command.roomEpoch());
        if (desiredChildId.equals(activeChildWorkflowId)) {
            return;
        }
        if (activeRoomChild != null) {
            activeRoomChild.close("ROOM_CONTROL_REPLACED");
        }
        RoomControlWorkflow child =
                Workflow.newChildWorkflowStub(
                        RoomControlWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId(desiredChildId)
                                .setTaskQueue(ROOM_CONTROL_TASK_QUEUE)
                                .setWorkflowIdReusePolicy(
                                        WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                                .setParentClosePolicy(PARENT_CLOSE_POLICY_ABANDON)
                                .build());
        RoomControlStart start =
                new RoomControlStart(
                        "room-control-start.v1",
                        command.tenantSurrogate(),
                        command.caseId(),
                        command.roomType(),
                        command.roomEpoch(),
                        Workflow.getInfo().getWorkflowId(),
                        command.caseCommandSequence(),
                        nextCaseEventSequence);
        Async.procedure(child::run, start);
        Workflow.getWorkflowExecution(child).get();
        activeRoomChild = child;
        activeRoomType = command.roomType();
        activeRoomEpoch = command.roomEpoch();
        activeChildWorkflowId = desiredChildId;
    }

    private void failGapRecovery(
            SequenceStream stream, long highestObserved, String reasonCode) {
        int attempts;
        if (stream == SequenceStream.COMMAND) {
            attempts = ++commandRecoveryAttempts;
        } else {
            attempts = ++eventRecoveryAttempts;
        }
        if (attempts >= MAX_GAP_RECOVERY_ATTEMPTS) {
            markManualRecovery(stream, highestObserved, reasonCode);
            return;
        }
        Workflow.sleep(GAP_RETRY_DELAY.multipliedBy(attempts));
    }

    private void markManualRecovery(
            SequenceStream stream, long highestObserved, String reasonCode) {
        if (stream == SequenceStream.COMMAND) {
            commandManualRecoveryRequired = true;
            commandRecoveryAttempts = Math.max(commandRecoveryAttempts, 1);
            protocolErrorCode = reasonCode;
        } else {
            eventManualRecoveryRequired = true;
            eventRecoveryAttempts = Math.max(eventRecoveryAttempts, 1);
            protocolErrorCode = reasonCode;
        }
        reportGap(stream, highestObserved, reasonCode);
    }

    private void reportGap(
            SequenceStream stream, long highestObserved, String reasonCode) {
        long expected =
                stream == SequenceStream.COMMAND
                        ? nextCommandSequence
                        : nextCaseEventSequence;
        int attempts =
                stream == SequenceStream.COMMAND
                        ? commandRecoveryAttempts
                        : eventRecoveryAttempts;
        try {
            ledgerActivities.reportSequenceGap(
                    new SequenceGapReport(
                            "sequence-gap-report.v1",
                            tenantSurrogate,
                            caseId,
                            Workflow.getInfo().getWorkflowId(),
                            Workflow.getInfo().getRunId(),
                            stream,
                            expected,
                            Math.max(expected, highestObserved),
                            Math.max(1, attempts),
                            reasonCode));
        } catch (ActivityFailure failure) {
            rethrowIfCanceled(failure);
        }
    }

    private void applyManualRecoveryRequest() {
        if (!retrySequenceGapRequested) {
            return;
        }
        retrySequenceGapRequested = false;
        commandManualRecoveryRequired = false;
        eventManualRecoveryRequired = false;
        commandRecoveryAttempts = 0;
        eventRecoveryAttempts = 0;
    }

    private void clearRecoveryError(SequenceStream stream) {
        if (protocolErrorCode == null) {
            return;
        }
        boolean recoverable =
                switch (stream) {
                    case COMMAND ->
                            protocolErrorCode.equals("COMMAND_SEQUENCE_NOT_FOUND")
                                    || protocolErrorCode.equals("COMMAND_LEDGER_UNAVAILABLE")
                                    || protocolErrorCode.equals("COMMAND_LEDGER_RESPONSE_INVALID")
                                    || protocolErrorCode.equals("COMMAND_REPLAY_LEDGER_UNAVAILABLE");
                    case DOMAIN_EVENT ->
                            protocolErrorCode.equals("DOMAIN_EVENT_SEQUENCE_NOT_FOUND")
                                    || protocolErrorCode.equals("DOMAIN_EVENT_LEDGER_UNAVAILABLE")
                                    || protocolErrorCode.equals("DOMAIN_EVENT_LEDGER_RESPONSE_INVALID")
                                    || protocolErrorCode.equals("CASE_PROCESS_EVENT_INBOX_FULL");
                };
        if (recoverable) {
            protocolErrorCode = null;
        }
    }

    private boolean hasWork() {
        return commandInboxCount > 0
                || eventInboxCount > 0
                || !replayChecks.isEmpty()
                || orderedCommands.containsKey(nextCommandSequence)
                || bufferedEvents.containsKey(nextCaseEventSequence)
                || (!commandManualRecoveryRequired && hasCommandGap())
                || (!eventManualRecoveryRequired && hasEventGap())
                || retrySequenceGapRequested
                || (shouldContinueAsNew() && canContinueAsNew());
    }

    private boolean hasCommandGap() {
        return highestObservedCommandSequence >= nextCommandSequence
                && !orderedCommands.containsKey(nextCommandSequence);
    }

    private boolean hasEventGap() {
        return (eventRecoveryForced
                        || highestObservedEventSequence >= nextCaseEventSequence)
                && !bufferedEvents.containsKey(nextCaseEventSequence);
    }

    private boolean shouldContinueAsNew() {
        return continueAsNewRequested
                || (runMaxAgeTimer != null && runMaxAgeTimer.isCompleted())
                || Workflow.getInfo().isContinueAsNewSuggested()
                || Workflow.getInfo().getHistoryLength() >= HISTORY_EVENT_LIMIT;
    }

    private boolean canContinueAsNew() {
        return commandInboxCount == 0
                && eventInboxCount == 0
                && orderedCommands.isEmpty()
                && replayChecks.isEmpty()
                && Workflow.isEveryHandlerFinished();
    }

    private void continueAsNew() {
        Workflow.await(Workflow::isEveryHandlerFinished);
        CaseProcessCarryState carry =
                new CaseProcessCarryState(
                        "case-process-carry-state.v1",
                        tenantSurrogate,
                        caseId,
                        activeRoomType,
                        activeRoomEpoch,
                        activeChildWorkflowId,
                        observedProcessRevision,
                        nextCommandSequence,
                        nextCaseEventSequence,
                        processedCommandCount,
                        processedEventCount,
                        new ArrayList<>(recentCommands.values()),
                        new ArrayList<>(bufferedEvents.values()),
                        highestObservedEventSequence,
                        runGeneration + 1,
                        commandRecoveryAttempts,
                        eventRecoveryAttempts,
                        commandManualRecoveryRequired,
                        eventManualRecoveryRequired,
                        protocolErrorCode);
        ContinueAsNewOptions options =
                ContinueAsNewOptions.newBuilder()
                        .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
                        .setMemo(Map.of(CARRY_STATE_MEMO_KEY, carry))
                        .build();
        // The optional input preserves zero-input starts while carrying state on test servers
        // that do not propagate Continue-As-New Memo; Memo remains the operational copy.
        Workflow.continueAsNew(options, carry);
    }

    private LoadSequenceRange range(long fromSequence, long toSequence) {
        return new LoadSequenceRange(
                "load-sequence-range.v1",
                tenantSurrogate,
                caseId,
                fromSequence,
                toSequence,
                Math.toIntExact(toSequence - fromSequence + 1));
    }

    private void bindIdentity(String incomingTenant, String incomingCaseId) {
        if (tenantSurrogate == null) {
            tenantSurrogate = incomingTenant;
            caseId = incomingCaseId;
            requireWorkflowIdentity(incomingTenant, incomingCaseId);
            return;
        }
        if (!tenantSurrogate.equals(incomingTenant) || !caseId.equals(incomingCaseId)) {
            throw protocolFailure(
                    "CASE_PROCESS_SCOPE_MISMATCH",
                    "workflow received an envelope for another case");
        }
    }

    private void requireWorkflowIdentity(String incomingTenant, String incomingCaseId) {
        String expected =
                CaseProcessWorkflowProtocol.caseWorkflowId(
                        incomingTenant, incomingCaseId);
        if (!Workflow.getInfo().getWorkflowId().equals(expected)) {
            throw protocolFailure(
                    "CASE_PROCESS_WORKFLOW_ID_MISMATCH",
                    "workflow id does not match the command scope");
        }
    }

    private void validateCommandEnvelope(CaseCommandRef command) {
        if (command == null) {
            throw protocolFailure(
                    "CASE_PROCESS_COMMAND_INVALID", "command must not be null");
        }
        requireWorkflowIdentity(command.tenantSurrogate(), command.caseId());
        if (tenantSurrogate != null
                && (!tenantSurrogate.equals(command.tenantSurrogate())
                        || !caseId.equals(command.caseId()))) {
            throw protocolFailure(
                    "CASE_PROCESS_SCOPE_MISMATCH",
                    "workflow received a command for another case");
        }
        if (command.caseCommandSequence() < 1
                || command.roomEpoch() < 0
                || command.expectedProcessRevision() < 0
                || command.payloadRef().sizeBytes() < 0
                || !SHA256.matcher(command.payloadRef().sha256()).matches()
                || !SHA256.matcher(command.requestHash()).matches()
                || !TRACEPARENT.matcher(command.traceparent()).matches()
                || !command.deadlineAt().isAfter(command.occurredAt())) {
            throw protocolFailure(
                    "CASE_PROCESS_COMMAND_INVALID",
                    "command envelope failed workflow validation");
        }
    }

    private String eventValidationError(CaseDomainEventRef event) {
        if (event == null) {
            return "CASE_PROCESS_EVENT_INVALID";
        }
        String expected =
                CaseProcessWorkflowProtocol.caseWorkflowId(
                        event.tenantSurrogate(), event.caseId());
        if (!Workflow.getInfo().getWorkflowId().equals(expected)) {
            return "CASE_PROCESS_WORKFLOW_ID_MISMATCH";
        }
        if (tenantSurrogate != null
                && (!tenantSurrogate.equals(event.tenantSurrogate())
                        || !caseId.equals(event.caseId()))) {
            return "CASE_PROCESS_SCOPE_MISMATCH";
        }
        return null;
    }

    private void validateLoadedCommand(
            CaseCommandRef command, long fromSequence, long toSequence) {
        validateCommandEnvelope(command);
        if (command.caseCommandSequence() < fromSequence
                || command.caseCommandSequence() > toSequence) {
            throw protocolFailure(
                    "COMMAND_LEDGER_RESPONSE_INVALID",
                    "command ledger returned an out-of-range command");
        }
    }

    private void validateLoadedEvent(
            CaseDomainEventRef event, long fromSequence, long toSequence) {
        String error = eventValidationError(event);
        if (error != null
                || event.caseEventSequence() < fromSequence
                || event.caseEventSequence() > toSequence) {
            throw protocolFailure(
                    "DOMAIN_EVENT_LEDGER_RESPONSE_INVALID",
                    "domain event ledger returned an invalid event");
        }
    }

    private String blockedReason() {
        if (commandManualRecoveryRequired) {
            return replayChecks.isEmpty()
                    ? "COMMAND_GAP_MANUAL_RECOVERY"
                    : "COMMAND_REPLAY_MANUAL_RECOVERY";
        }
        if (eventManualRecoveryRequired) {
            return "EVENT_GAP_MANUAL_RECOVERY";
        }
        if (hasCommandGap()) {
            return "COMMAND_GAP";
        }
        if (hasEventGap()) {
            return "EVENT_GAP";
        }
        return protocolErrorCode == null ? "NONE" : "PROTOCOL_ERROR";
    }

    private void trimRecentCommands() {
        while (recentCommands.size() > CaseProcessCarryState.MAX_RECENT_COMMANDS) {
            String first = recentCommands.keySet().iterator().next();
            recentCommands.remove(first);
        }
    }

    private static boolean sameCommand(CaseCommandRef left, CaseCommandRef right) {
        return left.commandId().equals(right.commandId())
                && left.caseCommandSequence() == right.caseCommandSequence()
                && left.requestHash().equals(right.requestHash());
    }

    private static boolean sameEvent(CaseDomainEventRef left, CaseDomainEventRef right) {
        return left.eventId().equals(right.eventId())
                && left.caseEventSequence() == right.caseEventSequence()
                && left.payloadRef().sha256().equals(right.payloadRef().sha256());
    }

    private static ApplicationFailure protocolFailure(String type, String message) {
        return ApplicationFailure.newNonRetryableFailure(message, type);
    }

    private static void rethrowIfCanceled(ActivityFailure failure) {
        if (failure.getCause() instanceof CanceledFailure) {
            throw failure;
        }
    }

    private static final class PendingCommand {
        private final CaseCommandRef command;
        private final List<CompletablePromise<Void>> completions;

        private PendingCommand(
                CaseCommandRef command, List<CompletablePromise<Void>> completions) {
            this.command = Objects.requireNonNull(command);
            this.completions = completions;
        }

        static PendingCommand live(
                CaseCommandRef command, CompletablePromise<Void> completion) {
            return new PendingCommand(command, new ArrayList<>(List.of(completion)));
        }

        static PendingCommand recovered(CaseCommandRef command) {
            return new PendingCommand(command, new ArrayList<>());
        }

        CaseCommandRef command() {
            return command;
        }

        void absorb(PendingCommand other) {
            completions.addAll(other.completions);
        }

        void complete() {
            completions.forEach(completion -> completion.complete(null));
        }

        void fail(RuntimeException failure) {
            completions.forEach(completion -> completion.completeExceptionally(failure));
        }
    }
}

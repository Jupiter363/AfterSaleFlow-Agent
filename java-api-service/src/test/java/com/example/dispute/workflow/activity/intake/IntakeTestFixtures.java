package com.example.dispute.workflow.activity.intake;

import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingConflictException;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingStore;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory.IssueRequest;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory.VersionPins;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

final class IntakeTestFixtures {

    static final String THREAD_ID = "grt.v1.018f6b7ec30a7430982fffc520c8195c";
    static final Instant ISSUED_AT = Instant.parse("2026-07-20T08:00:00Z");

    private IntakeTestFixtures() {}

    static IntakeGraphThreadBinding binding() {
        return binding("intake-prompt.v2");
    }

    static IntakeGraphThreadBinding binding(String promptVersion) {
        var factory = new IntakePrivateThreadRegistrationFactory(() -> THREAD_ID);
        return factory.issue(issueRequest(promptVersion));
    }

    static IssueRequest issueRequest(String promptVersion) {
        return new IssueRequest(
                        "REG_P4_INTAKE_USER_1",
                        "tenant-synthetic",
                        "CASE_P4_SYNTHETIC_1",
                        1,
                        2,
                        new IntakePrivateThreadRegistration.ActorScope(
                                "user-synthetic",
                                ActorRole.USER,
                                Audience.USER,
                                List.of("graph.command.execute")),
                        "AGENT_SESSION_P4_USER_1",
                        new VersionPins(
                                "2.0.0",
                                "intake-checkpoint.v2",
                                promptVersion,
                                "intake-model.synthetic.v1",
                                "intake-policy.v2",
                                "intake-guardrail.v2",
                                "no-tools.v1"),
                        WriterMode.SHADOW,
                        ISSUED_AT);
    }

    static IntakeSnapshotReference snapshot(IntakeGraphThreadBinding binding) {
        var registration = binding.registration();
        return new IntakeSnapshotReference(
                "SNAPSHOT_P4_USER_1",
                registration.registrationId(),
                registration.tenantSurrogate(),
                registration.caseId(),
                registration.roomEpoch(),
                binding.fencingToken(),
                registration.threadId(),
                registration.actorScopeHash(),
                registration.agentSessionId(),
                new RoomGraphCommand.SnapshotRef(
                        "SNAPSHOT_P4_USER_1",
                        "intake-domain-snapshot.v2",
                        "urn:intake:snapshot:SNAPSHOT_P4_USER_1",
                        "7deb1aa13efc125244aa0e122b6e02dc4343f97afc0423c94a00308b217fea0d",
                        1024),
                "version-1",
                4,
                2,
                4,
                1,
                Instant.parse("2026-07-20T08:01:00Z"));
    }

    static IntakeEventReference event(IntakeGraphThreadBinding binding) {
        var registration = binding.registration();
        return new IntakeEventReference(
                "EVENT_P4_USER_2",
                registration.registrationId(),
                "EVENT_P4_USER_2",
                "MESSAGE_P4_USER_2",
                registration.tenantSurrogate(),
                registration.caseId(),
                registration.roomEpoch(),
                binding.fencingToken(),
                registration.threadId(),
                registration.actorScopeHash(),
                registration.agentSessionId(),
                new RoomGraphCommand.SnapshotRef(
                        "EVENT_P4_USER_2",
                        "intake-turn-event.v2",
                        "urn:intake:event:EVENT_P4_USER_2",
                        "5da4ebd5b5ff75ea8af5c955c01f2cf18138892d07ad6ca74be7c7fb50ff5815",
                        512),
                "version-1",
                2,
                5,
                Audience.USER,
                Instant.parse("2026-07-20T08:02:00Z"),
                Instant.parse("2026-07-20T08:02:01Z"));
    }

    static final class SingleBindingStore implements IntakeGraphBindingStore {
        private IntakeGraphThreadBinding registration;
        private IntakeSnapshotReference snapshot;
        private IntakeEventReference event;

        @Override
        public Optional<IntakeGraphThreadBinding> findRegistration(String registrationId) {
            if (registration != null
                    && registration.registration().registrationId().equals(registrationId)) {
                return Optional.of(registration);
            }
            return Optional.empty();
        }

        @Override
        public WriteReceipt<IntakeGraphThreadBinding> register(IntakeGraphThreadBinding value) {
            if (registration == null) {
                registration = value;
                return WriteReceipt.created(value);
            }
            if (!registration.equals(value)) {
                throw new IntakeGraphBindingConflictException("registration conflict");
            }
            return WriteReceipt.replayed(registration);
        }

        @Override
        public WriteReceipt<IntakeSnapshotReference> bindInitialSnapshot(
                IntakeSnapshotReference value) {
            if (snapshot == null) {
                snapshot = value;
                return WriteReceipt.created(value);
            }
            if (!snapshot.equals(value)) {
                throw new IntakeGraphBindingConflictException("snapshot conflict");
            }
            return WriteReceipt.replayed(snapshot);
        }

        @Override
        public WriteReceipt<IntakeEventReference> bindEvent(IntakeEventReference value) {
            if (snapshot == null) {
                throw new IntakeGraphBindingConflictException("event before snapshot");
            }
            if (event == null) {
                event = value;
                return WriteReceipt.created(value);
            }
            if (!event.equals(value)) {
                throw new IntakeGraphBindingConflictException("event conflict");
            }
            return WriteReceipt.replayed(event);
        }
    }
}

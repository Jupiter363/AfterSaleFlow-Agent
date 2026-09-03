package com.example.dispute.workflow.application.intake;

import java.util.Objects;

/** Persists an exact PENDING binding; the registration outbox delivery is a separate boundary. */
public final class IntakePrivateThreadRegistrar {

    private final IntakeGraphBindingStore store;
    private final IntakePrivateThreadRegistrationFactory factory;

    public IntakePrivateThreadRegistrar(IntakeGraphBindingStore store) {
        this(store, new IntakePrivateThreadRegistrationFactory());
    }

    public IntakePrivateThreadRegistrar(
            IntakeGraphBindingStore store, IntakePrivateThreadRegistrationFactory factory) {
        this.store = Objects.requireNonNull(store, "store");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public IntakeGraphBindingStore.WriteReceipt<IntakeGraphThreadBinding> register(
            IntakePrivateThreadRegistrationFactory.IssueRequest request) {
        Objects.requireNonNull(request, "request");
        var existing = store.findRegistration(request.registrationId());
        if (existing.isPresent()) {
            requireIssueRequestMatches(request, existing.get());
            return IntakeGraphBindingStore.WriteReceipt.replayed(existing.get());
        }
        IntakeGraphBindingStore.WriteReceipt<IntakeGraphThreadBinding> receipt =
                Objects.requireNonNull(store.register(factory.issue(request)), "registration receipt");
        requireIssueRequestMatches(request, receipt.value());
        return receipt;
    }

    public IntakeGraphBindingStore.WriteReceipt<IntakeGraphThreadBinding> register(
            IntakeGraphThreadBinding binding) {
        Objects.requireNonNull(binding, "binding");
        binding.registration().requireCanonicalHash();
        IntakeGraphBindingStore.WriteReceipt<IntakeGraphThreadBinding> receipt =
                Objects.requireNonNull(store.register(binding), "registration receipt");
        if (!binding.equals(receipt.value())) {
            throw new IntakeGraphBindingConflictException(
                    "persisted private thread registration differs from the request");
        }
        return receipt;
    }

    private static void requireIssueRequestMatches(
            IntakePrivateThreadRegistrationFactory.IssueRequest request,
            IntakeGraphThreadBinding binding) {
        IntakePrivateThreadRegistration registration = binding.registration();
        var pins = request.versionPins();
        boolean matches =
                request.registrationId().equals(registration.registrationId())
                        && request.tenantSurrogate().equals(registration.tenantSurrogate())
                        && request.caseId().equals(registration.caseId())
                        && request.roomEpoch() == registration.roomEpoch()
                        && request.fencingToken() == binding.fencingToken()
                        && request.actorScope().equals(registration.actorScope())
                        && request.agentSessionId().equals(registration.agentSessionId())
                        && pins.graphVersion().equals(registration.graphVersion())
                        && pins.checkpointSchemaVersion()
                                .equals(registration.checkpointSchemaVersion())
                        && pins.promptVersion().equals(registration.promptVersion())
                        && pins.modelProfileId().equals(registration.modelProfileId())
                        && pins.policyVersion().equals(registration.policyVersion())
                        && pins.guardrailVersion().equals(registration.guardrailVersion())
                        && pins.toolPolicyVersion().equals(registration.toolPolicyVersion())
                        && request.writerMode() == registration.writerMode();
        if (!matches) {
            throw new IntakeGraphBindingConflictException(
                    "registration id is already bound to different private-thread material");
        }
        registration.requireCanonicalHash();
    }
}

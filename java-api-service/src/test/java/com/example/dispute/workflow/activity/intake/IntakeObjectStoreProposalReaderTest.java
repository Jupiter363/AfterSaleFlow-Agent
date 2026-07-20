package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeImmutableProposalReader;
import com.example.dispute.workflow.application.intake.IntakeObjectStoreProposalReader;
import com.example.dispute.workflow.application.intake.IntakeProposalLoadException;
import com.example.dispute.workflow.application.intake.IntakeProposalObjectStoreGateway;
import com.example.dispute.workflow.application.intake.IntakeProposalObjectStoreGateway.PermanentAccessException;
import com.example.dispute.workflow.application.intake.IntakeProposalObjectStoreGateway.Reason;
import com.example.dispute.workflow.application.intake.IntakeProposalObjectStoreGateway.RetryableAccessException;
import com.example.dispute.workflow.application.intake.IntakeProposalObjectStoreGateway.RetryableReason;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.application.intake.IntakeProposalUriAllowlist;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IntakeObjectStoreProposalReaderTest {

    @Test
    void mapsOnlyExplicitTransientAccessFailuresToTheRetryableType() {
        IntakeProposalReference reference = reference(
                "s3://private-intake/intake/proposals/case-1/proposal.json");
        var transientFailure = new RetryableAccessException(
                RetryableReason.SERVER_ERROR,
                "object store returned 503",
                new IllegalStateException("503"));
        var reader = reader(ignored -> {
            throw transientFailure;
        });

        assertThatThrownBy(() -> reader.load(reference))
                .isInstanceOf(IntakeProposalLoadException.class)
                .hasCause(transientFailure);
    }

    @Test
    void mapsPermanentObjectResultsToNonRetryableRejections() {
        assertPermanent(Reason.NOT_FOUND, "INTAKE_PROPOSAL_OBJECT_NOT_FOUND");
        assertPermanent(
                Reason.VERSION_MISMATCH,
                "INTAKE_PROPOSAL_OBJECT_VERSION_MISMATCH");
        assertPermanent(
                Reason.ACCESS_DENIED,
                "INTAKE_PROPOSAL_OBJECT_ACCESS_DENIED");
        assertPermanent(
                Reason.REFERENCE_INVALID,
                "INTAKE_PROPOSAL_OBJECT_REFERENCE_INVALID");
    }

    @Test
    void rejectsUnknownGatewayFailuresInsteadOfRetryingThem() {
        var reader = reader(ignored -> {
            throw new IllegalStateException("unclassified SDK exception");
        });

        assertRejected(
                "INTAKE_PROPOSAL_ACCESS_UNCLASSIFIED",
                () -> reader.load(reference(
                        "s3://private-intake/intake/proposals/case-1/proposal.json")));
    }

    @Test
    void enforcesAuthorityAndPrefixBeforeCallingTheObjectStore() {
        AtomicInteger calls = new AtomicInteger();
        var reader = reader(reference -> {
            calls.incrementAndGet();
            return stored(reference);
        });

        assertRejected(
                "INTAKE_PROPOSAL_URI_FORBIDDEN",
                () -> reader.load(reference(
                        "s3://other-bucket/intake/proposals/case-1/proposal.json")));
        assertRejected(
                "INTAKE_PROPOSAL_URI_FORBIDDEN",
                () -> reader.load(reference(
                        "s3://private-intake/public/proposal.json")));
        assertRejected(
                "INTAKE_PROPOSAL_URI_FORBIDDEN",
                () -> reader.load(reference(
                        "s3://private-intake/intake/proposals/case-1/proposal.json?version=other")));
        assertThat(calls).hasValue(0);
    }

    @Test
    void permitsOnlyConfiguredS3AndUrnLocations() {
        AtomicInteger calls = new AtomicInteger();
        var reader = reader(reference -> {
            calls.incrementAndGet();
            return stored(reference);
        });

        assertThat(reader.load(reference(
                        "s3://private-intake/intake/proposals/case-1/proposal.json")))
                .isNotNull();
        assertThat(reader.load(reference("urn:intake:proposal:case-1:v1")))
                .isNotNull();
        assertThat(calls).hasValue(2);
    }

    private static void assertPermanent(Reason reason, String code) {
        var reader = reader(ignored -> {
            throw new PermanentAccessException(reason, "permanent object result");
        });

        assertRejected(
                code,
                () -> reader.load(reference(
                        "s3://private-intake/intake/proposals/case-1/proposal.json")));
    }

    private static IntakeObjectStoreProposalReader reader(
            IntakeProposalObjectStoreGateway gateway) {
        return new IntakeObjectStoreProposalReader(
                gateway,
                new IntakeProposalUriAllowlist(List.of(
                        new IntakeProposalUriAllowlist.Rule(
                                "s3", "private-intake", "/intake/proposals/"),
                        new IntakeProposalUriAllowlist.Rule(
                                "urn", null, "intake:proposal:"))));
    }

    private static IntakeProposalReference reference(String uri) {
        return new IntakeProposalReference(
                "PROPOSAL_TEST_1",
                "intake-turn-proposal.v2",
                uri,
                "version-1",
                "a".repeat(64),
                12);
    }

    private static IntakeImmutableProposalReader.StoredProposal stored(
            IntakeProposalReference reference) {
        return new IntakeImmutableProposalReader.StoredProposal(
                reference.artifactId(),
                reference.schemaVersion(),
                reference.uri(),
                reference.objectVersion(),
                reference.sha256(),
                reference.sizeBytes(),
                new byte[(int) reference.sizeBytes()]);
    }

    private static void assertRejected(String code, ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .satisfies(failure -> assertThat(((IntakeFinalizationRejectedException) failure).code())
                        .isEqualTo(code));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}

package com.example.dispute.workflow.shadow.intake;

import static com.example.dispute.workflow.shadow.IntakeSyntheticTestFixtures.AGENT_SESSION;
import static com.example.dispute.workflow.shadow.IntakeSyntheticTestFixtures.ACTOR_SCOPE;
import static com.example.dispute.workflow.shadow.IntakeSyntheticTestFixtures.THREAD_ID;
import static com.example.dispute.workflow.shadow.IntakeSyntheticTestFixtures.finalizationRequest;
import static com.example.dispute.workflow.shadow.IntakeSyntheticTestFixtures.hash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakeRuntimeMaterialObjectStore;
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakeRuntimeMaterialObjectStore.StoredObject;
import com.example.dispute.workflow.infrastructure.objectstore.intake.PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource;
import com.example.dispute.workflow.shadow.intake.IntakeRuntimeMaterialManifestReferenceSource.ManifestObjectReference;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.ActivityAuthority;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticSnapshotMaterialSource.SnapshotMaterialQuery;
import com.example.dispute.workflow.shadow.intake.MountedIntakeRuntimeMaterialManifestReferenceSource.ActivityLookup;
import com.example.dispute.workflow.shadow.intake.MountedIntakeRuntimeMaterialManifestReferenceSource.ManifestBinding;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntakeSyntheticRuntimeMaterialSourceTest {

    private static final Path VALID_MANIFEST = Path.of(
            "..",
            "contracts",
            "agent-platform",
            "intake",
            "v2",
            "fixtures",
            "valid",
            "intake-synthetic-runtime-material-manifest-valid.json");

    @Test
    void concreteProviderLoadsSnapshotFromAnExactSyntheticManifest() throws Exception {
        Fixture fixture = fixture();
        PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource source =
                source(fixture, exactStore(fixture));

        var loaded = source.load(new SnapshotMaterialQuery(
                fixture.authority(),
                threadBinding(fixture.manifest()),
                fixture.manifest().snapshotMaterial().roomRevision()));

        assertThat(loaded.snapshotId())
                .isEqualTo(fixture.manifest().snapshotMaterial().snapshot().artifactId());
        assertThat(loaded.ownMessages())
                .hasSameSizeAs(fixture.manifest().snapshotMaterial().ownMessages());
    }

    @Test
    void concreteProviderRejectsANonExactObjectStoreReceipt() throws Exception {
        Fixture fixture = fixture();
        IntakeRuntimeMaterialObjectStore wrongReceipt = request -> new StoredObject(
                request.artifactId(),
                request.schemaVersion(),
                request.uri() + ".other",
                request.objectVersion(),
                request.contentHash(),
                request.sizeBytes(),
                fixture.content());
        PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource source =
                source(fixture, wrongReceipt);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> source.load(new SnapshotMaterialQuery(
                        fixture.authority(),
                        threadBinding(fixture.manifest()),
                        fixture.manifest().snapshotMaterial().roomRevision())))
                .withMessageContaining("non-exact runtime material receipt");
    }

    @Test
    void concreteProviderRevalidatesAuthorityAfterReferenceResolution() throws Exception {
        Fixture fixture = fixture();
        ActivityAuthority admitted = fixture.authority();
        ActivityAuthority mismatched = new ActivityAuthority(
                admitted.envelope(),
                admitted.threadId(),
                admitted.agentSessionId(),
                admitted.operationKey(),
                hash(98));
        PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource source =
                new PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource(
                        fixture.mapper(), ignored -> fixture.reference(), exactStore(fixture));

        assertThatThrownBy(() -> source.load(new SnapshotMaterialQuery(
                        mismatched,
                        threadBinding(fixture.manifest()),
                        fixture.manifest().snapshotMaterial().roomRevision())))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("authority does not match");
    }

    @Test
    void mountedIndexResolvesOnlyTheExactAdmittedActivityAuthority() {
        ActivityAuthority authority = authority();
        ManifestObjectReference reference = manifestReference();
        MountedIntakeRuntimeMaterialManifestReferenceSource source =
                new MountedIntakeRuntimeMaterialManifestReferenceSource(
                        List.of(new ManifestBinding(ActivityLookup.from(authority), reference)));

        assertThat(source.resolve(authority)).isSameAs(reference);

        ActivityAuthority mismatched = new ActivityAuthority(
                authority.envelope(),
                authority.threadId(),
                authority.agentSessionId(),
                authority.operationKey(),
                hash(99));
        assertThatThrownBy(() -> source.resolve(mismatched))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("no synthetic runtime material manifest is admitted");
    }

    @Test
    void mountedIndexRejectsDuplicateAuthorityBindings() {
        ActivityAuthority authority = authority();
        ManifestBinding binding =
                new ManifestBinding(ActivityLookup.from(authority), manifestReference());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MountedIntakeRuntimeMaterialManifestReferenceSource(
                        List.of(binding, binding)))
                .withMessageContaining("duplicate activity authority");
    }

    private static ActivityAuthority authority() {
        var request = finalizationRequest(
                "CMD_RUNTIME_MATERIAL", IntakeParty.INITIATOR, ACTOR_SCOPE, hash(30));
        return new ActivityAuthority(
                request.envelope(),
                THREAD_ID,
                AGENT_SESSION,
                request.operationKey(),
                request.requestHash());
    }

    private static ManifestObjectReference manifestReference() {
        return new ManifestObjectReference(
                ManifestObjectReference.SCHEMA_VERSION,
                "MANIFEST_RUNTIME_MATERIAL",
                "minio://intake-synthetic-private/runtime-material/manifest.json",
                "version-1",
                hash(31),
                1024);
    }

    private static Fixture fixture() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        byte[] content = ContractJson.canonicalize(
                mapper.readTree(Files.readAllBytes(VALID_MANIFEST)));
        IntakeRuntimeMaterialManifest manifest =
                mapper.readValue(content, IntakeRuntimeMaterialManifest.class);
        ActivityAuthority authority = authority(manifest);
        ManifestObjectReference reference = new ManifestObjectReference(
                ManifestObjectReference.SCHEMA_VERSION,
                manifest.manifestId(),
                "minio://intake-synthetic-private/signed-synthetic/intake/runtime-material/"
                        + "manifest.json",
                "version-1",
                manifest.manifestHash(),
                content.length);
        return new Fixture(mapper, manifest, authority, reference, content);
    }

    private static ActivityAuthority authority(IntakeRuntimeMaterialManifest manifest) {
        var binding = manifest.authorityBinding();
        var pins = manifest.versionPins();
        PinnedVersions pinnedVersions = new PinnedVersions(
                "intake-pinned-versions.v1",
                pins.roomWorkflowBuildId(),
                pins.graphVersion(),
                pins.checkpointSchemaVersion(),
                pins.promptVersion(),
                pins.modelProfileId(),
                pins.outputSchemaVersion(),
                pins.policyVersion(),
                pins.guardrailVersion(),
                pins.toolPolicyVersion());
        ActivityEnvelope envelope = new ActivityEnvelope(
                "intake-activity-envelope.v1",
                binding.tenantSurrogate(),
                binding.caseId(),
                binding.roomEpoch(),
                binding.fencingToken(),
                binding.commandId(),
                binding.commandSequence(),
                binding.commandType(),
                binding.party(),
                binding.actorScopeHash(),
                binding.commandPayloadRef(),
                binding.commandPayloadHash(),
                binding.processRevision(),
                binding.roomRevision(),
                binding.deadlineEpochMillis(),
                new RetryBudget("intake-retry-budget.v1", 2, 1, 1),
                pinnedVersions,
                new ActivityInvocation(
                        "intake-activity-invocation.v1",
                        ActivityInvocationMode.FIRST_EXECUTION,
                        2));
        return new ActivityAuthority(
                envelope,
                binding.threadId(),
                binding.agentSessionId(),
                binding.commandOperationKey(),
                binding.requestHash());
    }

    private static IntakeGraphThreadBinding threadBinding(
            IntakeRuntimeMaterialManifest manifest) {
        var authority = manifest.authorityBinding();
        var pins = manifest.versionPins();
        IntakeGraphThreadBinding binding = mock(IntakeGraphThreadBinding.class);
        IntakePrivateThreadRegistration registration =
                mock(IntakePrivateThreadRegistration.class);
        IntakePrivateThreadRegistration.ActorScope actor =
                mock(IntakePrivateThreadRegistration.ActorScope.class);
        when(binding.registration()).thenReturn(registration);
        when(binding.fencingToken()).thenReturn(authority.fencingToken());
        when(registration.actorScope()).thenReturn(actor);
        when(registration.registrationId()).thenReturn(authority.registrationId());
        when(registration.tenantSurrogate()).thenReturn(authority.tenantSurrogate());
        when(registration.caseId()).thenReturn(authority.caseId());
        when(registration.roomEpoch()).thenReturn(authority.roomEpoch());
        when(registration.threadId()).thenReturn(authority.threadId());
        when(registration.actorScopeHash()).thenReturn(authority.actorScopeHash());
        when(registration.agentSessionId()).thenReturn(authority.agentSessionId());
        when(registration.registrationHash()).thenReturn(authority.registrationHash());
        when(registration.graphKey()).thenReturn(pins.graphKey());
        when(registration.graphVersion()).thenReturn(pins.graphVersion());
        when(registration.checkpointSchemaVersion())
                .thenReturn(pins.checkpointSchemaVersion());
        when(actor.actorId()).thenReturn(authority.actorId());
        when(actor.actorRole()).thenReturn(authority.actorRole());
        when(actor.audience()).thenReturn(authority.audience());
        return binding;
    }

    private static PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource source(
            Fixture fixture, IntakeRuntimeMaterialObjectStore store) {
        var references = new MountedIntakeRuntimeMaterialManifestReferenceSource(List.of(
                new ManifestBinding(ActivityLookup.from(fixture.authority()), fixture.reference())));
        return new PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource(
                fixture.mapper(), references, store);
    }

    private static IntakeRuntimeMaterialObjectStore exactStore(Fixture fixture) {
        return request -> new StoredObject(
                request.artifactId(),
                request.schemaVersion(),
                request.uri(),
                request.objectVersion(),
                request.contentHash(),
                request.sizeBytes(),
                fixture.content());
    }

    private record Fixture(
            ObjectMapper mapper,
            IntakeRuntimeMaterialManifest manifest,
            ActivityAuthority authority,
            ManifestObjectReference reference,
            byte[] content) {}
}

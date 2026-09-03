package com.example.dispute.workflow.infrastructure.objectstore.intake;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher.OwnMessage;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakeRuntimeMaterialObjectStore.ReadRequest;
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakeRuntimeMaterialObjectStore.StoredObject;
import com.example.dispute.workflow.shadow.intake.IntakeRuntimeMaterialManifest;
import com.example.dispute.workflow.shadow.intake.IntakeRuntimeMaterialManifest.ArtifactReference;
import com.example.dispute.workflow.shadow.intake.IntakeRuntimeMaterialManifest.AuthorityBinding;
import com.example.dispute.workflow.shadow.intake.IntakeRuntimeMaterialManifest.ParitySnapshot;
import com.example.dispute.workflow.shadow.intake.IntakeRuntimeMaterialManifest.SyntheticScalarField;
import com.example.dispute.workflow.shadow.intake.IntakeRuntimeMaterialManifestReferenceSource;
import com.example.dispute.workflow.shadow.intake.IntakeRuntimeMaterialManifestReferenceSource.ManifestObjectReference;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.Dimension;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.ObservedValue;
import com.example.dispute.workflow.shadow.intake.IntakeShadowParityService;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticGraphMaterialSource;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticGraphMaterialSource.ArtifactMaterial;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticGraphMaterialSource.GraphPlan;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticGraphMaterialSource.GraphPlanQuery;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityMaterialSource;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityMaterialSource.ParityMaterial;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityMaterialSource.ParityMaterialQuery;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.ActivityAuthority;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.GraphArtifactQuery;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticSnapshotMaterialSource;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticSnapshotMaterialSource.SnapshotMaterialQuery;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ImmutablePayloadRef;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Private-object-store provider for the engineering-only signed-synthetic Intake material lane.
 *
 * <p>All lookups start from a mounted, exact activity-authority index. Manifest declarations are
 * restrictions only; this class never turns them into formal business authority.
 */
public final class PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource
        implements IntakeSyntheticSnapshotMaterialSource,
                IntakeSyntheticGraphMaterialSource,
                IntakeSyntheticParityMaterialSource {

    private final ObjectMapper mapper;
    private final IntakeRuntimeMaterialManifestReferenceSource references;
    private final IntakeRuntimeMaterialObjectStore objectStore;
    private final Map<ManifestObjectReference, CachedManifest> manifests =
            new ConcurrentHashMap<>();

    public PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource(
            ObjectMapper objectMapper,
            IntakeRuntimeMaterialManifestReferenceSource references,
            IntakeRuntimeMaterialObjectStore objectStore) {
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.references = Objects.requireNonNull(references, "references");
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
    }

    @Override
    public IntakeSyntheticSnapshotMaterialSource.SnapshotMaterial load(
            SnapshotMaterialQuery query) {
        Objects.requireNonNull(query, "query");
        IntakeRuntimeMaterialManifest manifest = manifest(query.authority());
        requireThread(manifest, query.threadBinding());
        IntakeRuntimeMaterialManifest.SnapshotMaterial material = manifest.snapshotMaterial();
        if (material.roomRevision() != query.authoritativeRoomRevision()) {
            throw rejected("snapshot material room revision is stale");
        }
        requireSnapshotMessages(manifest);
        ObjectNode initialCaseFacts = scalarObject(material.initialCaseFacts());
        ObjectNode shareableProjection = scalarObject(material.shareableProjection());
        ObjectNode currentDossier = scalarObject(material.currentDossier());
        List<OwnMessage> ownMessages = material.ownMessages().stream()
                .map(message -> new OwnMessage(
                        message.messageId(),
                        message.role(),
                        message.audience(),
                        message.sequence(),
                        message.text(),
                        message.sourceHash()))
                .toList();
        return new IntakeSyntheticSnapshotMaterialSource.SnapshotMaterial(
                material.snapshot().artifactId(),
                material.domainRevision(),
                material.projectionRevision(),
                material.sourceRefs(),
                initialCaseFacts,
                shareableProjection,
                ownMessages,
                currentDossier,
                material.createdAt());
    }

    @Override
    public GraphPlan loadPlan(GraphPlanQuery query) {
        Objects.requireNonNull(query, "query");
        IntakeRuntimeMaterialManifest manifest = manifest(query.authority());
        requireThread(manifest, query.threadBinding());
        requireGraphQuery(manifest, query);
        var plan = manifest.graphPlan();
        return new GraphPlan(
                plan.logicalRunId(),
                plan.attemptId(),
                plan.attemptNo(),
                plan.attemptLimit(),
                plan.previousAttemptId(),
                plan.resetRequired(),
                plan.publicSequenceOffset(),
                plan.stageCode(),
                plan.agentProfileId(),
                plan.operation(),
                plan.logicalIdempotencyKey(),
                plan.envelopeKeyId(),
                plan.envelopeNonce());
    }

    @Override
    public ArtifactMaterial loadArtifacts(GraphArtifactQuery query) {
        Objects.requireNonNull(query, "query");
        var request = query.activityRequest();
        ActivityAuthority authority = new ActivityAuthority(
                request.envelope(),
                request.threadId(),
                request.agentSessionId(),
                request.operationKey(),
                request.requestHash());
        IntakeRuntimeMaterialManifest manifest = manifest(authority);
        requireGraphArtifactLineage(manifest, query);
        ArtifactReference resultReference = manifest.graphArtifacts().result();
        ArtifactReference proposalReference = manifest.graphArtifacts().proposal();
        RoomGraphResult storedResult = readTypedArtifact(
                resultReference, "output_hash", RoomGraphResult.class);
        if (!storedResult.equals(query.result())) {
            throw rejected("stored Graph result differs from the returned Graph result");
        }
        IntakeTurnProposal proposal = readTypedArtifact(
                proposalReference, "proposal_hash", IntakeTurnProposal.class);
        requireProposal(manifest, query, proposal);
        return new ArtifactMaterial(payloadRef(resultReference), payloadRef(proposalReference));
    }

    @Override
    public ParityMaterial load(ParityMaterialQuery query) {
        Objects.requireNonNull(query, "query");
        IntakeRuntimeMaterialManifest manifest = manifest(query.authority());
        var parity = manifest.parityMaterial();
        var execution = query.request().graphExecution();
        if (!parity.parityBaseline().objectUri().equals(query.parityBaselineRef())
                || !parity.parityBaseline().contentSha256().equals(query.parityBaselineHash())
                || !parity.resultHash().equals(execution.operation().resultHash())
                || !parity.proposalHash().equals(
                        execution.graphExecutionRef().proposalHash())) {
            throw rejected("parity material differs from admitted Graph lineage");
        }
        readBaseline(parity.parityBaseline());
        return new ParityMaterial(
                paritySnapshot(parity.legacy()),
                paritySnapshot(parity.shadow()),
                parity.projectedEventType());
    }

    private IntakeRuntimeMaterialManifest manifest(ActivityAuthority authority) {
        ManifestObjectReference reference = Objects.requireNonNull(
                references.resolve(authority), "manifest reference");
        CachedManifest cached = manifests.computeIfAbsent(reference, this::loadManifest);
        IntakeRuntimeMaterialManifest manifest = cached.manifest();
        manifest.authorityBinding().requireExact(authority);
        manifest.versionPins().requireExact(authority.envelope().pinnedVersions());
        if (!manifest.manifestId().equals(reference.artifactId())
                || !manifest.manifestHash().equals(reference.contentHash())) {
            throw rejected("runtime manifest identity differs from its trusted reference");
        }
        return manifest;
    }

    private CachedManifest loadManifest(ManifestObjectReference reference) {
        ReadRequest request = new ReadRequest(
                reference.artifactId(),
                IntakeRuntimeMaterialManifest.SCHEMA_VERSION,
                reference.uri(),
                reference.objectVersion(),
                reference.contentHash(),
                reference.sizeBytes(),
                IntakeRuntimeMaterialManifest.MAX_ENCODED_BYTES);
        byte[] content = requireExact(request, objectStore.readExact(request));
        JsonNode document = parseCanonical(content, "runtime material manifest");
        requireSelfHash(document, "manifest_hash", reference.contentHash(), "manifest");
        requireNestedHash(document, "authority_binding", "authority_binding_hash");
        requireNestedHash(document, "version_pins", "pin_set_hash");
        IntakeRuntimeMaterialManifest manifest = decode(
                content, IntakeRuntimeMaterialManifest.class, "runtime material manifest");
        return new CachedManifest(manifest);
    }

    private void requireThread(
            IntakeRuntimeMaterialManifest manifest, IntakeGraphThreadBinding binding) {
        IntakePrivateThreadRegistration registration = binding.registration();
        AuthorityBinding authority = manifest.authorityBinding();
        var actor = registration.actorScope();
        if (!registration.registrationId().equals(authority.registrationId())
                || !registration.tenantSurrogate().equals(authority.tenantSurrogate())
                || !registration.caseId().equals(authority.caseId())
                || registration.roomEpoch() != authority.roomEpoch()
                || binding.fencingToken() != authority.fencingToken()
                || !registration.threadId().equals(authority.threadId())
                || !registration.actorScopeHash().equals(authority.actorScopeHash())
                || !registration.agentSessionId().equals(authority.agentSessionId())
                || !registration.registrationHash().equals(authority.registrationHash())
                || !registration.graphKey().equals(manifest.versionPins().graphKey())
                || !registration.graphVersion().equals(manifest.versionPins().graphVersion())
                || !registration.checkpointSchemaVersion()
                        .equals(manifest.versionPins().checkpointSchemaVersion())
                || !actor.actorId().equals(authority.actorId())
                || actor.actorRole() != authority.actorRole()
                || actor.audience() != authority.audience()) {
            throw rejected("runtime material thread binding is not exact");
        }
    }

    private void requireSnapshotMessages(IntakeRuntimeMaterialManifest manifest) {
        IntakeRuntimeMaterialManifest.SnapshotMaterial snapshot = manifest.snapshotMaterial();
        var expectedAudience = manifest.authorityBinding().audience();
        long previousSequence = -1;
        for (var message : snapshot.ownMessages()) {
            if (message.audience() != expectedAudience
                    || !snapshot.sourceRefs().contains(message.messageId())
                    || message.sequence() <= previousSequence) {
                throw rejected("snapshot message crosses its private authority or sequence");
            }
            previousSequence = message.sequence();
        }
    }

    private void requireGraphQuery(
            IntakeRuntimeMaterialManifest manifest, GraphPlanQuery query) {
        AuthorityBinding authority = manifest.authorityBinding();
        var plan = manifest.graphPlan();
        if (!authority.epochId().equals(query.epochId())
                || !authority.logicalRunId().equals(query.admittedLogicalRunId())
                || !authority.attemptId().equals(query.admittedAttemptId())
                || !plan.logicalRunId().equals(query.admittedLogicalRunId())
                || !plan.attemptId().equals(query.admittedAttemptId())) {
            throw rejected("Graph plan is outside admitted run lineage");
        }
        var snapshot = query.initialSnapshot();
        ArtifactReference snapshotRef = manifest.snapshotMaterial().snapshot();
        if (!snapshot.threadRegistrationId().equals(authority.registrationId())
                || !snapshot.tenantSurrogate().equals(authority.tenantSurrogate())
                || !snapshot.caseId().equals(authority.caseId())
                || snapshot.roomEpoch() != authority.roomEpoch()
                || snapshot.fencingToken() != authority.fencingToken()
                || !snapshot.threadId().equals(authority.threadId())
                || !snapshot.actorScopeHash().equals(authority.actorScopeHash())
                || !snapshot.agentSessionId().equals(authority.agentSessionId())
                || !same(snapshot.payloadRef(), snapshotRef)
                || !snapshot.objectVersion().equals(snapshotRef.objectVersion())) {
            throw rejected("Graph plan initial snapshot binding is not exact");
        }
        var event = query.event();
        if (!event.threadRegistrationId().equals(authority.registrationId())
                || !event.tenantSurrogate().equals(authority.tenantSurrogate())
                || !event.caseId().equals(authority.caseId())
                || event.roomEpoch() != authority.roomEpoch()
                || event.fencingToken() != authority.fencingToken()
                || !event.threadId().equals(authority.threadId())
                || !event.actorScopeHash().equals(authority.actorScopeHash())
                || !event.agentSessionId().equals(authority.agentSessionId())
                || !event.payloadRef().uri().equals(authority.commandPayloadRef())
                || !event.payloadRef().sha256().equals(authority.commandPayloadHash())) {
            throw rejected("Graph plan event binding is not exact");
        }
    }

    private void requireGraphArtifactLineage(
            IntakeRuntimeMaterialManifest manifest, GraphArtifactQuery query) {
        var result = query.result();
        var command = query.command();
        var artifacts = manifest.graphArtifacts();
        var operation = result.artifactOperations().getFirst().artifact();
        if (!manifest.authorityBinding().logicalRunId().equals(command.logicalRunId())
                || !manifest.authorityBinding().attemptId().equals(command.attemptId())
                || !command.commandId().equals(result.commandId())
                || !command.logicalRunId().equals(result.logicalRunId())
                || !command.attemptId().equals(result.attemptId())
                || !artifacts.result().objectUri().equals(query.resultRef())
                || !artifacts.result().contentSha256().equals(result.outputHash())
                || !artifacts.proposal().artifactId().equals(operation.artifactId())
                || !artifacts.proposal().schemaVersion().equals(operation.schemaVersion())
                || !artifacts.proposal().objectUri().equals(operation.uri())
                || !artifacts.proposal().contentSha256().equals(operation.sha256())) {
            throw rejected("Graph artifacts differ from command/result lineage");
        }
    }

    private void requireProposal(
            IntakeRuntimeMaterialManifest manifest,
            GraphArtifactQuery query,
            IntakeTurnProposal proposal) {
        var authority = manifest.authorityBinding();
        var pins = manifest.versionPins();
        var reference = manifest.graphArtifacts().proposal();
        if (!proposal.commandId().equals(query.command().commandId())
                || !proposal.logicalRunId().equals(query.command().logicalRunId())
                || !proposal.attemptId().equals(query.command().attemptId())
                || !proposal.caseId().equals(authority.caseId())
                || proposal.roomEpoch() != authority.roomEpoch()
                || !proposal.threadId().equals(authority.threadId())
                || !proposal.actorScopeHash().equals(authority.actorScopeHash())
                || !proposal.agentSessionId().equals(authority.agentSessionId())
                || proposal.cognitiveRevision() != query.result().cognitiveRevision()
                || !proposal.sourceSnapshotHash()
                        .equals(manifest.snapshotMaterial().snapshot().contentSha256())
                || !Objects.equals(proposal.sourceEventHash(), authority.commandPayloadHash())
                || !proposal.proposalHash().equals(reference.contentSha256())
                || !proposal.profileVersions().graphVersion().equals(pins.graphVersion())
                || !proposal.profileVersions().checkpointSchemaVersion()
                        .equals(pins.checkpointSchemaVersion())
                || !proposal.profileVersions().promptVersion().equals(pins.promptVersion())
                || !proposal.profileVersions().modelProfileId().equals(pins.modelProfileId())
                || !proposal.profileVersions().outputSchemaVersion()
                        .equals(pins.outputSchemaVersion())
                || !proposal.profileVersions().policyVersion().equals(pins.policyVersion())
                || !proposal.profileVersions().guardrailVersion()
                        .equals(pins.guardrailVersion())
                || !proposal.profileVersions().toolPolicyVersion()
                        .equals(pins.toolPolicyVersion())) {
            throw rejected("stored proposal differs from admitted material lineage");
        }
    }

    private <T> T readTypedArtifact(
            ArtifactReference reference, String hashField, Class<T> type) {
        ReadRequest request = readRequest(reference);
        byte[] content = requireExact(request, objectStore.readExact(request));
        JsonNode document = parseCanonical(content, reference.artifactType());
        requireSchema(document, reference.schemaVersion());
        requireSelfHash(document, hashField, reference.contentSha256(), reference.artifactType());
        return decode(content, type, reference.artifactType());
    }

    private void readBaseline(ArtifactReference reference) {
        ReadRequest request = readRequest(reference);
        byte[] content = requireExact(request, objectStore.readExact(request));
        JsonNode document = parseCanonical(content, "parity baseline");
        requireSchema(document, reference.schemaVersion());
        JsonNode selfHash = document.get("baseline_hash");
        String actual = selfHash != null && selfHash.isTextual()
                ? IntakeContractHashes.canonicalHashExcluding(document, "baseline_hash")
                : ContractJson.sha256Hex(document);
        if (!reference.contentSha256().equals(actual)) {
            throw rejected("parity baseline hash differs from its immutable reference");
        }
    }

    private ReadRequest readRequest(ArtifactReference reference) {
        int maximum = switch (reference.artifactType()) {
            case "GRAPH_RESULT", "INTAKE_PROPOSAL" ->
                    IntakeRuntimeMaterialManifest.GRAPH_ARTIFACT_MAX_BYTES;
            case "PARITY_BASELINE" ->
                    IntakeRuntimeMaterialManifest.PARITY_BASELINE_MAX_BYTES;
            case "INTAKE_SNAPSHOT" -> IntakeRuntimeMaterialManifest.SNAPSHOT_MAX_BYTES;
            default -> throw rejected("runtime artifact type is not loadable");
        };
        return new ReadRequest(
                reference.artifactId(),
                reference.schemaVersion(),
                reference.objectUri(),
                reference.objectVersion(),
                reference.contentSha256(),
                reference.sizeBytes(),
                maximum);
    }

    private byte[] requireExact(ReadRequest request, StoredObject stored) {
        Objects.requireNonNull(stored, "stored runtime material object");
        byte[] content = stored.content();
        if (!request.artifactId().equals(stored.artifactId())
                || !request.schemaVersion().equals(stored.schemaVersion())
                || !request.uri().equals(stored.uri())
                || !request.objectVersion().equals(stored.objectVersion())
                || !request.contentHash().equals(stored.contentHash())
                || request.sizeBytes() != stored.sizeBytes()
                || content == null
                || content.length != request.sizeBytes()) {
            throw rejected("object store returned a non-exact runtime material receipt");
        }
        return content;
    }

    private JsonNode parseCanonical(byte[] content, String label) {
        try {
            JsonNode document = mapper.readTree(content);
            if (document == null || !document.isObject()) {
                throw rejected(label + " must be a JSON object");
            }
            if (!Arrays.equals(content, ContractJson.canonicalize(document))) {
                throw rejected(label + " must use canonical JSON encoding");
            }
            return document;
        } catch (IOException failure) {
            throw new IllegalArgumentException(label + " is not valid unique-member JSON", failure);
        }
    }

    private <T> T decode(byte[] content, Class<T> type, String label) {
        try {
            return mapper.readerFor(type).readValue(content);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalArgumentException(label + " does not match its strict Java model", failure);
        }
    }

    private static void requireNestedHash(
            JsonNode root, String objectField, String hashField) {
        JsonNode value = root.get(objectField);
        if (value == null || !value.isObject()) {
            throw rejected(objectField + " must be a JSON object");
        }
        JsonNode expected = value.get(hashField);
        if (expected == null
                || !expected.isTextual()
                || !IntakeContractHashes.canonicalHashExcluding(value, hashField)
                        .equals(expected.textValue())) {
            throw rejected(objectField + " self-hash is invalid");
        }
    }

    private static void requireSelfHash(
            JsonNode document, String hashField, String expectedHash, String label) {
        JsonNode selfHash = document.get(hashField);
        String actual = IntakeContractHashes.canonicalHashExcluding(document, hashField);
        if (selfHash == null
                || !selfHash.isTextual()
                || !actual.equals(selfHash.textValue())
                || !actual.equals(expectedHash)) {
            throw rejected(label + " self-hash differs from its immutable reference");
        }
    }

    private static void requireSchema(JsonNode document, String expected) {
        JsonNode schema = document.get("schema_version");
        if (schema == null || !schema.isTextual() || !expected.equals(schema.textValue())) {
            throw rejected("runtime material object schema is not exact");
        }
    }

    private static ObjectNode scalarObject(List<SyntheticScalarField> fields) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        fields.forEach(field -> result.set(field.name(), field.value()));
        return result;
    }

    private static boolean same(
            RoomGraphCommand.SnapshotRef actual, ArtifactReference expected) {
        return actual.artifactId().equals(expected.artifactId())
                && actual.schemaVersion().equals(expected.schemaVersion())
                && actual.uri().equals(expected.objectUri())
                && actual.sha256().equals(expected.contentSha256())
                && actual.sizeBytes() == expected.sizeBytes();
    }

    private static ImmutablePayloadRef payloadRef(ArtifactReference reference) {
        return new ImmutablePayloadRef(
                "immutable-payload-ref.v1",
                reference.artifactId(),
                reference.artifactType(),
                reference.schemaVersion(),
                reference.objectUri(),
                reference.objectVersion(),
                reference.contentSha256(),
                reference.sizeBytes());
    }

    private static IntakeShadowParityService.ParitySnapshot paritySnapshot(
            ParitySnapshot source) {
        EnumMap<Dimension, ObservedValue> values = new EnumMap<>(Dimension.class);
        source.values().forEach((dimension, value) -> values.put(
                dimension, new ObservedValue(value.classification(), value.valueHash())));
        return new IntakeShadowParityService.ParitySnapshot(
                values, source.hardZeroFindings());
    }

    private static IllegalArgumentException rejected(String message) {
        return new IllegalArgumentException(message);
    }

    private record CachedManifest(IntakeRuntimeMaterialManifest manifest) {
        private CachedManifest {
            Objects.requireNonNull(manifest, "manifest");
        }
    }
}

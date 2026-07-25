package com.example.dispute.agentstream.infrastructure.persistence;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore.CompatibilityReport;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamRetentionManifest.ArchiveReceipt;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists and verifies delivery-only V046 archive evidence. This store deliberately exposes no
 * partition detach/drop operation and cannot produce complete production-release evidence.
 */
@Repository
public class AgentRunStreamArchiveStore {

    private static final String AUTHORITY_SCOPE = "DELIVERY_STORAGE_ONLY";
    private static final String LOAD_FORMAL_BINDING_SQL =
            """
            select run.final_stream_sequence_no,
                   execution.id as execution_manifest_id,
                   execution.manifest_sha256 as execution_manifest_sha256,
                   execution.finalized_at,
                   terminal.id as terminal_event_id,
                   terminal.payload_hash as terminal_payload_sha256,
                   true as terminal_event_retained,
                   true as immutable_manifest_retained
              from agent_run run
              join agent_execution_manifest execution
                on execution.id = run.committed_manifest_id
               and execution.logical_agent_run_id = run.id
               and execution.attempt_id = run.committed_attempt_id
               and execution.manifest_sha256 = run.committed_manifest_hash
               and execution.output_sha256 = run.final_result_hash
               and execution.terminal_status = 'COMPLETED'
              join agent_run_stream_event terminal
                on terminal.agent_run_id = run.id
               and terminal.agent_run_attempt_id = run.committed_attempt_id
               and terminal.sequence_no = run.final_stream_sequence_no
               and terminal.stream_protocol = 'agent-stream.v2'
               and terminal.event_type = 'final'
             where run.id = ? and run.committed_attempt_id = ?
               and run.finalization_status = 'COMMITTED'
            """;

    private static final String LOAD_ARCHIVE_EVENTS_SQL =
            """
            select event.event_id, event.sequence_no, event.event_type,
                   event.canonical_payload_sha256, event.recorded_at,
                   event.tableoid::regclass::text as target_partition_name
              from agent_run_stream_event_delivery event
             where event.stream_protocol = ? and event.agent_run_id = ?
               and event.agent_run_attempt_id = ?
             order by event.sequence_no asc
            """;

    private static final String LOAD_DELIVERY_HIGH_WATERMARK_SQL =
            """
            select highest_contiguous_sequence_no
              from agent_run_stream_delivery_high_watermark
             where stream_protocol = ? and agent_run_id = ?
               and agent_run_attempt_id = ?
             for key share
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PostgresAgentRunV2EventStore compatibilityStore;
    private final TransactionTemplate transaction;

    public AgentRunStreamArchiveStore(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PostgresAgentRunV2EventStore compatibilityStore,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.compatibilityStore =
                Objects.requireNonNull(compatibilityStore, "compatibilityStore");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Records one verified immutable archive and returns its exact durable receipt. */
    public ArchiveReceipt recordVerifiedArchive(ArchiveRequest request) {
        Objects.requireNonNull(request, "request").requireValid();
        CompatibilityReport compatibility = validatedCompatibility(
                request.streamProtocol(), request.runId(), request.attemptId());
        ArchiveReceipt receipt =
                transaction.execute(status -> recordInTransaction(request, compatibility));
        if (receipt == null) {
            throw new IllegalStateException("archive transaction returned no receipt");
        }
        return receipt;
    }

    /** Loads the formal baseline and attaches only exact durable V046 archive evidence. */
    public Optional<AgentRunStreamRetentionManifest> retentionManifest(
            String runId, String attemptId) {
        return compatibilityStore.retentionManifest(runId, attemptId)
                .map(manifest -> findVerifiedReceipt(manifest)
                .map(manifest::withArchiveReceipt)
                .orElse(manifest));
    }

    private Optional<ArchiveReceipt> findVerifiedReceipt(
            AgentRunStreamRetentionManifest retention) {
        Objects.requireNonNull(retention, "retention");
        CompatibilityReport currentCompatibility = validatedCompatibility(
                "agent-stream.v2", retention.runId(), retention.attemptId());
        String currentCompatibilityHash = ContractJson.sha256Hex(
                objectMapper.valueToTree(currentCompatibility));
        List<ArchiveReceipt> receipts = jdbc.query(
                """
                select receipt.receipt_id, receipt.receipt_sha256,
                       receipt.schema_version as receipt_schema_version,
                       receipt.manifest_id, receipt.manifest_sha256,
                       receipt.target_partition_name, receipt.agent_run_id,
                       receipt.agent_run_attempt_id, receipt.first_sequence_no,
                       receipt.last_sequence_no, receipt.event_count,
                       receipt.canonical_events_sha256, receipt.object_version,
                       receipt.object_sha256, receipt.object_readback_sha256,
                       receipt.sequence_identity_validation_json::text,
                       receipt.audience_cursor_validation_json::text,
                       receipt.delivery_high_watermark,
                       receipt.hot_retention_started_at,
                       receipt.hot_retention_eligible_at,
                       receipt.verified_at, receipt.verified_by,
                       receipt.receipt_status, receipt.authority_scope,
                       receipt.formal_business_authority,
                       manifest.partition_range_start,
                       manifest.partition_range_end,
                       manifest.schema_version as manifest_schema_version,
                       manifest.authority_scope as manifest_authority_scope,
                       manifest.formal_business_authority as manifest_formal_business_authority,
                       manifest.object_uri, manifest.created_by,
                       manifest.terminal_event_id,
                       manifest.terminal_event_sha256,
                       manifest.agent_execution_manifest_id,
                       manifest.agent_execution_manifest_sha256,
                       exists (
                           select 1 from agent_run_stream_event terminal
                            where terminal.id = manifest.terminal_event_id
                              and terminal.stream_protocol = 'agent-stream.v2'
                              and terminal.agent_run_id = manifest.agent_run_id
                              and terminal.agent_run_attempt_id = manifest.agent_run_attempt_id
                              and terminal.sequence_no = manifest.last_sequence_no
                              and terminal.event_type = 'final'
                              and terminal.payload_hash = manifest.terminal_event_sha256
                       ) as terminal_event_retained,
                       exists (
                           select 1
                             from agent_run current_run
                             join agent_execution_manifest current_execution
                               on current_execution.id = current_run.committed_manifest_id
                              and current_execution.logical_agent_run_id = current_run.id
                              and current_execution.attempt_id = current_run.committed_attempt_id
                              and current_execution.manifest_sha256 =
                                  current_run.committed_manifest_hash
                              and current_execution.output_sha256 =
                                  current_run.final_result_hash
                              and current_execution.terminal_status = 'COMPLETED'
                            where current_run.id = manifest.agent_run_id
                              and current_run.committed_attempt_id =
                                  manifest.agent_run_attempt_id
                              and current_run.finalization_status = 'COMMITTED'
                              and current_run.final_stream_sequence_no =
                                  manifest.last_sequence_no
                              and current_execution.id =
                                  manifest.agent_execution_manifest_id
                              and current_execution.manifest_sha256 =
                                  manifest.agent_execution_manifest_sha256
                              and current_execution.finalized_at =
                                  receipt.hot_retention_started_at
                       ) as immutable_manifest_retained,
                       watermark.highest_contiguous_sequence_no
                  from agent_run_stream_archive_receipt receipt
                  join agent_run_stream_archive_manifest manifest
                    on manifest.manifest_id = receipt.manifest_id
                   and manifest.manifest_sha256 = receipt.manifest_sha256
                  join agent_run_stream_delivery_high_watermark watermark
                    on watermark.stream_protocol = receipt.stream_protocol
                   and watermark.agent_run_id = receipt.agent_run_id
                   and watermark.agent_run_attempt_id = receipt.agent_run_attempt_id
                 where receipt.receipt_status = 'VERIFIED'
                   and receipt.stream_protocol = 'agent-stream.v2'
                   and receipt.agent_run_id = ?
                   and receipt.agent_run_attempt_id = ?
                   and receipt.first_sequence_no = 0
                   and receipt.last_sequence_no = ?
                   and manifest.terminal_event_sha256 = ?
                   and manifest.agent_execution_manifest_id = ?
                   and manifest.agent_execution_manifest_sha256 = ?
                 order by receipt.verified_at desc, receipt.receipt_id desc
                """,
                (resultSet, rowNumber) -> {
                    JsonNode validation = readJson(
                            resultSet.getString("sequence_identity_validation_json"));
                    String sequenceValidation = canonicalJson(
                            resultSet.getString("sequence_identity_validation_json"));
                    String audienceValidation = canonicalJson(
                            resultSet.getString("audience_cursor_validation_json"));
                    String objectCreationReceiptId =
                            requiredText(validation, "object_creation_receipt_id");
                    String objectCreationReceiptHash =
                            requiredText(validation, "object_creation_receipt_sha256");
                    String expectedSequenceValidation = canonicalJson(json(
                            new SequenceValidationDocument(
                                    "agent-stream-sequence-identity-validation.v1",
                                    "PASS",
                                    currentCompatibility.sequenceParity(),
                                    currentCompatibility.sequenceParity()
                                            && currentCompatibility.canonicalHashParity(),
                                    objectCreationReceiptId,
                                    objectCreationReceiptHash,
                                    currentCompatibilityHash,
                                    currentCompatibility.sourceCount(),
                                    currentCompatibility.targetCount(),
                                    false)));
                    String expectedAudienceValidation = canonicalJson(json(
                            new AudienceValidationDocument(
                                    "agent-stream-audience-cursor-validation.v1",
                                    "PASS",
                                    currentCompatibility.audienceParity()
                                            && currentCompatibility.visibilityParity(),
                                    currentCompatibility.actorIdParity(),
                                    currentCompatibility.compositeCursorParity()
                                            && currentCompatibility.reconnectParity()
                                            && currentCompatibility.resetParity()
                                            && currentCompatibility.terminalParity(),
                                    currentCompatibilityHash,
                                    false)));
                    if (!currentCompatibilityHash.equals(
                                    requiredText(validation, "compatibility_report_sha256"))
                            || currentCompatibility.sourceCount()
                                    != validation.path("source_event_count").asLong(-1)
                            || currentCompatibility.targetCount()
                                    != validation.path("target_event_count").asLong(-1)
                            || !expectedSequenceValidation.equals(sequenceValidation)
                            || !expectedAudienceValidation.equals(audienceValidation)) {
                        throw new IllegalStateException(
                                "archive receipt is not bound to current exact stream parity");
                    }
                    long receiptHighWatermark =
                            resultSet.getLong("delivery_high_watermark");
                    long durableHighWatermark =
                            resultSet.getLong("highest_contiguous_sequence_no");
                    if (receiptHighWatermark > durableHighWatermark) {
                        throw new IllegalStateException(
                                "archive receipt leads the durable delivery high-watermark");
                    }
                    ManifestDocument manifestDocument = new ManifestDocument(
                            "agent-stream-archive-manifest.v1",
                            resultSet.getString("manifest_id"),
                            resultSet.getString("target_partition_name"),
                            resultSet.getTimestamp("partition_range_start").toInstant(),
                            resultSet.getTimestamp("partition_range_end").toInstant(),
                            "agent-stream.v2",
                            resultSet.getString("agent_run_id"),
                            resultSet.getString("agent_run_attempt_id"),
                            resultSet.getLong("first_sequence_no"),
                            resultSet.getLong("last_sequence_no"),
                            resultSet.getLong("event_count"),
                            resultSet.getString("canonical_events_sha256"),
                            resultSet.getString("object_uri"),
                            resultSet.getString("object_version"),
                            resultSet.getString("object_sha256"),
                            resultSet.getString("terminal_event_id"),
                            resultSet.getString("terminal_event_sha256"),
                            resultSet.getString("agent_execution_manifest_id"),
                            resultSet.getString("agent_execution_manifest_sha256"),
                            objectCreationReceiptId,
                            objectCreationReceiptHash,
                            AUTHORITY_SCOPE,
                            false,
                            resultSet.getString("created_by"));
                    String recomputedManifestHash = ContractJson.sha256Hex(
                            objectMapper.valueToTree(manifestDocument));
                    if (!recomputedManifestHash.equals(
                                    resultSet.getString("manifest_sha256"))
                            || !"agent-stream-archive-manifest.v1".equals(
                                    resultSet.getString("manifest_schema_version"))
                            || !AUTHORITY_SCOPE.equals(
                                    resultSet.getString("manifest_authority_scope"))
                            || resultSet.getBoolean("manifest_formal_business_authority")) {
                        throw new IllegalStateException(
                                "archive manifest hash does not match immutable content");
                    }
                    ReceiptDocument receiptDocument = new ReceiptDocument(
                            "agent-stream-archive-receipt.v1",
                            resultSet.getString("receipt_id"),
                            resultSet.getString("manifest_id"),
                            recomputedManifestHash,
                            resultSet.getString("target_partition_name"),
                            "agent-stream.v2",
                            resultSet.getString("agent_run_id"),
                            resultSet.getString("agent_run_attempt_id"),
                            resultSet.getLong("first_sequence_no"),
                            resultSet.getLong("last_sequence_no"),
                            resultSet.getLong("event_count"),
                            resultSet.getString("canonical_events_sha256"),
                            resultSet.getString("object_version"),
                            resultSet.getString("object_sha256"),
                            resultSet.getString("object_readback_sha256"),
                            ContractJson.sha256Hex(readJson(sequenceValidation)),
                            ContractJson.sha256Hex(readJson(audienceValidation)),
                            receiptHighWatermark,
                            resultSet.getTimestamp("hot_retention_started_at").toInstant(),
                            resultSet.getTimestamp("hot_retention_eligible_at").toInstant(),
                            resultSet.getTimestamp("verified_at").toInstant(),
                            resultSet.getString("verified_by"),
                            AUTHORITY_SCOPE,
                            false,
                            false);
                    String recomputedReceiptHash = ContractJson.sha256Hex(
                            objectMapper.valueToTree(receiptDocument));
                    boolean terminalEventRetained =
                            resultSet.getBoolean("terminal_event_retained");
                    boolean immutableManifestRetained =
                            resultSet.getBoolean("immutable_manifest_retained");
                    if (!recomputedReceiptHash.equals(resultSet.getString("receipt_sha256"))
                            || !"agent-stream-archive-receipt.v1".equals(
                                    resultSet.getString("receipt_schema_version"))
                            || !"VERIFIED".equals(resultSet.getString("receipt_status"))
                            || !AUTHORITY_SCOPE.equals(resultSet.getString("authority_scope"))
                            || resultSet.getBoolean("formal_business_authority")
                            || !terminalEventRetained
                            || !immutableManifestRetained
                            || validation.path("release_evidence_complete").asBoolean(true)
                            || readJson(audienceValidation)
                                    .path("release_evidence_complete")
                                    .asBoolean(true)) {
                        throw new IllegalStateException(
                                "archive receipt hash or delivery-only scope is invalid");
                    }
                    return new ArchiveReceipt(
                            resultSet.getString("receipt_id"),
                            recomputedReceiptHash,
                            resultSet.getString("manifest_id"),
                            resultSet.getString("manifest_sha256"),
                            resultSet.getString("target_partition_name"),
                            resultSet.getString("agent_run_id"),
                            resultSet.getString("agent_run_attempt_id"),
                            resultSet.getLong("first_sequence_no"),
                            resultSet.getLong("last_sequence_no"),
                            resultSet.getLong("event_count"),
                            resultSet.getString("canonical_events_sha256"),
                            resultSet.getString("object_version"),
                            resultSet.getString("object_sha256"),
                            resultSet.getString("object_readback_sha256"),
                            objectCreationReceiptId,
                            objectCreationReceiptHash,
                            receiptHighWatermark,
                            resultSet.getTimestamp("hot_retention_started_at").toInstant(),
                            resultSet.getTimestamp("hot_retention_eligible_at").toInstant(),
                            resultSet.getString("terminal_event_sha256"),
                            resultSet.getString("agent_execution_manifest_id"),
                            resultSet.getString("agent_execution_manifest_sha256"),
                            terminalEventRetained,
                            immutableManifestRetained,
                            resultSet.getBoolean("formal_business_authority"),
                            false);
                },
                retention.runId(),
                retention.attemptId(),
                retention.terminalSequenceNo(),
                retention.terminalPayloadHash(),
                retention.agentExecutionManifestId(),
                retention.agentExecutionManifestHash());
        return receipts.stream().findFirst();
    }

    private ArchiveReceipt recordInTransaction(
            ArchiveRequest request, CompatibilityReport compatibility) {
        CompatibilityReport transactionCompatibility = validatedCompatibility(
                request.streamProtocol(), request.runId(), request.attemptId());
        String compatibilityHash = ContractJson.sha256Hex(
                objectMapper.valueToTree(compatibility));
        if (!compatibility.equals(transactionCompatibility)
                || !compatibilityHash.equals(ContractJson.sha256Hex(
                        objectMapper.valueToTree(transactionCompatibility)))) {
            throw new IllegalStateException(
                    "stream parity changed while recording archive evidence");
        }
        FormalBinding formal = loadFormalBinding(request.runId(), request.attemptId());
        AgentRunStreamRetentionManifest authoritativeRetention = compatibilityStore
                .retentionManifest(request.runId(), request.attemptId())
                .orElseThrow(() -> new IllegalStateException(
                        "archive requires an authoritative formal retention manifest"));
        requireFormalBinding(authoritativeRetention, formal);
        List<ArchivedEventIdentity> events = loadArchiveEvents(request, formal);
        long deliveryHighWatermark = loadDeliveryHighWatermark(request);
        if (deliveryHighWatermark != formal.terminalSequenceNo()) {
            throw new IllegalStateException(
                    "archive requires the exact durable terminal delivery high-watermark");
        }

        String canonicalEventsHash =
                ContractJson.sha256Hex(objectMapper.valueToTree(events));
        ManifestDocument manifestDocument = new ManifestDocument(
                "agent-stream-archive-manifest.v1",
                request.manifestId(),
                request.targetPartitionName(),
                request.partitionRangeStart(),
                request.partitionRangeEnd(),
                request.streamProtocol(),
                request.runId(),
                request.attemptId(),
                0,
                formal.terminalSequenceNo(),
                events.size(),
                canonicalEventsHash,
                request.objectUri(),
                request.objectVersion(),
                request.objectHash(),
                formal.terminalEventId(),
                formal.terminalPayloadHash(),
                formal.executionManifestId(),
                formal.executionManifestHash(),
                request.objectCreationReceiptId(),
                request.objectCreationReceiptHash(),
                AUTHORITY_SCOPE,
                false,
                request.createdBy());
        String manifestHash =
                ContractJson.sha256Hex(objectMapper.valueToTree(manifestDocument));
        StoredManifest expectedManifest = new StoredManifest(
                request.manifestId(),
                manifestHash,
                request.targetPartitionName(),
                request.partitionRangeStart(),
                request.partitionRangeEnd(),
                request.streamProtocol(),
                request.runId(),
                request.attemptId(),
                0,
                formal.terminalSequenceNo(),
                events.size(),
                canonicalEventsHash,
                request.objectUri(),
                request.objectVersion(),
                request.objectHash(),
                formal.terminalEventId(),
                formal.terminalPayloadHash(),
                formal.executionManifestId(),
                formal.executionManifestHash(),
                "agent-stream-archive-manifest.v1",
                AUTHORITY_SCOPE,
                false,
                request.createdBy());
        persistOrVerifyManifest(expectedManifest);

        String sequenceValidation = json(new SequenceValidationDocument(
                "agent-stream-sequence-identity-validation.v1",
                "PASS",
                compatibility.sequenceParity(),
                compatibility.sequenceParity() && compatibility.canonicalHashParity(),
                request.objectCreationReceiptId(),
                request.objectCreationReceiptHash(),
                compatibilityHash,
                compatibility.sourceCount(),
                compatibility.targetCount(),
                false));
        String audienceValidation = json(new AudienceValidationDocument(
                "agent-stream-audience-cursor-validation.v1",
                "PASS",
                compatibility.audienceParity() && compatibility.visibilityParity(),
                compatibility.actorIdParity(),
                compatibility.compositeCursorParity()
                        && compatibility.reconnectParity()
                        && compatibility.resetParity()
                        && compatibility.terminalParity(),
                compatibilityHash,
                false));
        Instant retentionEligibleAt = formal.finalizedAt()
                .plus(AgentRunStreamRetentionManifest.MINIMUM_HOT_RETENTION);
        ReceiptDocument receiptDocument = new ReceiptDocument(
                "agent-stream-archive-receipt.v1",
                request.receiptId(),
                request.manifestId(),
                manifestHash,
                request.targetPartitionName(),
                request.streamProtocol(),
                request.runId(),
                request.attemptId(),
                0,
                formal.terminalSequenceNo(),
                events.size(),
                canonicalEventsHash,
                request.objectVersion(),
                request.objectHash(),
                request.objectReadbackHash(),
                ContractJson.sha256Hex(readJson(sequenceValidation)),
                ContractJson.sha256Hex(readJson(audienceValidation)),
                deliveryHighWatermark,
                formal.finalizedAt(),
                retentionEligibleAt,
                request.verifiedAt(),
                request.verifiedBy(),
                AUTHORITY_SCOPE,
                false,
                false);
        String receiptHash =
                ContractJson.sha256Hex(objectMapper.valueToTree(receiptDocument));
        StoredReceipt expectedReceipt = new StoredReceipt(
                request.receiptId(),
                receiptHash,
                request.manifestId(),
                manifestHash,
                request.targetPartitionName(),
                request.streamProtocol(),
                request.runId(),
                request.attemptId(),
                0,
                formal.terminalSequenceNo(),
                events.size(),
                canonicalEventsHash,
                request.objectVersion(),
                request.objectHash(),
                request.objectReadbackHash(),
                canonicalJson(sequenceValidation),
                canonicalJson(audienceValidation),
                deliveryHighWatermark,
                formal.finalizedAt(),
                retentionEligibleAt,
                "VERIFIED",
                AUTHORITY_SCOPE,
                false,
                request.verifiedAt(),
                request.verifiedBy());
        persistOrVerifyReceipt(expectedReceipt);

        return new ArchiveReceipt(
                request.receiptId(),
                receiptHash,
                request.manifestId(),
                manifestHash,
                request.targetPartitionName(),
                request.runId(),
                request.attemptId(),
                0,
                formal.terminalSequenceNo(),
                events.size(),
                canonicalEventsHash,
                request.objectVersion(),
                request.objectHash(),
                request.objectReadbackHash(),
                request.objectCreationReceiptId(),
                request.objectCreationReceiptHash(),
                deliveryHighWatermark,
                formal.finalizedAt(),
                retentionEligibleAt,
                formal.terminalPayloadHash(),
                formal.executionManifestId(),
                formal.executionManifestHash(),
                formal.terminalEventRetained(),
                formal.immutableManifestRetained(),
                false,
                false);
    }

    private FormalBinding loadFormalBinding(String runId, String attemptId) {
        List<FormalBinding> bindings = jdbc.query(
                LOAD_FORMAL_BINDING_SQL,
                (resultSet, rowNumber) -> new FormalBinding(
                        resultSet.getLong("final_stream_sequence_no"),
                        resultSet.getString("terminal_event_id"),
                        resultSet.getString("terminal_payload_sha256"),
                        resultSet.getString("execution_manifest_id"),
                        resultSet.getString("execution_manifest_sha256"),
                        resultSet.getTimestamp("finalized_at").toInstant(),
                        resultSet.getBoolean("terminal_event_retained"),
                        resultSet.getBoolean("immutable_manifest_retained")),
                runId,
                attemptId);
        if (bindings.size() != 1) {
            throw new IllegalStateException(
                    "archive requires one formally committed terminal AgentRun manifest");
        }
        return bindings.getFirst();
    }

    private static void requireFormalBinding(
            AgentRunStreamRetentionManifest retention, FormalBinding formal) {
        if (retention.terminalSequenceNo() != formal.terminalSequenceNo()
                || !retention.terminalPayloadHash().equals(formal.terminalPayloadHash())
                || !retention.agentExecutionManifestId().equals(formal.executionManifestId())
                || !retention.agentExecutionManifestHash().equals(formal.executionManifestHash())
                || !retention.finalizedAt().equals(formal.finalizedAt())
                || !formal.terminalEventRetained()
                || !formal.immutableManifestRetained()) {
            throw new IllegalStateException(
                    "archive formal binding conflicts with authoritative retention evidence");
        }
    }

    private List<ArchivedEventIdentity> loadArchiveEvents(
            ArchiveRequest request, FormalBinding formal) {
        List<ArchivedEventIdentity> events = jdbc.query(
                LOAD_ARCHIVE_EVENTS_SQL,
                (resultSet, rowNumber) -> new ArchivedEventIdentity(
                        resultSet.getString("event_id"),
                        resultSet.getLong("sequence_no"),
                        resultSet.getString("event_type"),
                        resultSet.getString("canonical_payload_sha256"),
                        resultSet.getTimestamp("recorded_at").toInstant(),
                        resultSet.getString("target_partition_name")),
                request.streamProtocol(),
                request.runId(),
                request.attemptId());
        if (events.size() != formal.terminalSequenceNo() + 1) {
            throw new IllegalStateException("archive event count is not terminal-contiguous");
        }
        boolean terminal = false;
        for (int index = 0; index < events.size(); index++) {
            ArchivedEventIdentity event = events.get(index);
            if (event.sequenceNo() != index
                    || terminal
                    || !request.targetPartitionName().equals(event.targetPartitionName())
                    || event.recordedAt().isBefore(request.partitionRangeStart())
                    || !event.recordedAt().isBefore(request.partitionRangeEnd())) {
                throw new IllegalStateException(
                        "archive rows are gapped, post-terminal, or outside one target partition");
            }
            terminal = isTerminal(event.eventType());
        }
        ArchivedEventIdentity last = events.getLast();
        if (!terminal
                || last.sequenceNo() != formal.terminalSequenceNo()
                || !last.eventId().equals(formal.terminalEventId())
                || !last.payloadHash().equals(formal.terminalPayloadHash())) {
            throw new IllegalStateException(
                    "archive does not retain the exact formal terminal event");
        }
        return List.copyOf(events);
    }

    private long loadDeliveryHighWatermark(ArchiveRequest request) {
        List<Long> values = jdbc.query(
                LOAD_DELIVERY_HIGH_WATERMARK_SQL,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                request.streamProtocol(),
                request.runId(),
                request.attemptId());
        if (values.size() != 1) {
            throw new IllegalStateException("durable delivery high-watermark is missing");
        }
        return values.getFirst();
    }

    private CompatibilityReport validatedCompatibility(
            String streamProtocol, String runId, String attemptId) {
        CompatibilityReport report = compatibilityStore.validateCompatibility(
                streamProtocol, runId, attemptId);
        return report.requireCompatible();
    }

    private void persistOrVerifyManifest(StoredManifest expected) {
        jdbc.update(
                """
                insert into agent_run_stream_archive_manifest (
                    manifest_id, manifest_sha256, target_partition_name,
                    partition_range_start, partition_range_end, stream_protocol,
                    agent_run_id, agent_run_attempt_id, first_sequence_no,
                    last_sequence_no, event_count, canonical_events_sha256,
                    object_uri, object_version, object_sha256,
                    terminal_event_id, terminal_event_sha256,
                    agent_execution_manifest_id, agent_execution_manifest_sha256,
                    created_by
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (manifest_id) do nothing
                """,
                expected.manifestId(), expected.manifestHash(), expected.targetPartitionName(),
                Timestamp.from(expected.partitionRangeStart()),
                Timestamp.from(expected.partitionRangeEnd()), expected.streamProtocol(),
                expected.runId(), expected.attemptId(), expected.firstSequenceNo(),
                expected.lastSequenceNo(), expected.eventCount(),
                expected.canonicalEventsHash(), expected.objectUri(), expected.objectVersion(),
                expected.objectHash(), expected.terminalEventId(),
                expected.terminalPayloadHash(), expected.executionManifestId(),
                expected.executionManifestHash(), expected.createdBy());
        List<StoredManifest> stored = jdbc.query(
                """
                select manifest_id, manifest_sha256, target_partition_name,
                       partition_range_start, partition_range_end, stream_protocol,
                       agent_run_id, agent_run_attempt_id, first_sequence_no,
                       last_sequence_no, event_count, canonical_events_sha256,
                       object_uri, object_version, object_sha256,
                       terminal_event_id, terminal_event_sha256,
                       agent_execution_manifest_id, agent_execution_manifest_sha256,
                       schema_version, authority_scope, formal_business_authority,
                       created_by
                  from agent_run_stream_archive_manifest where manifest_id = ?
                """,
                (resultSet, rowNumber) -> new StoredManifest(
                        resultSet.getString("manifest_id"),
                        resultSet.getString("manifest_sha256"),
                        resultSet.getString("target_partition_name"),
                        resultSet.getTimestamp("partition_range_start").toInstant(),
                        resultSet.getTimestamp("partition_range_end").toInstant(),
                        resultSet.getString("stream_protocol"),
                        resultSet.getString("agent_run_id"),
                        resultSet.getString("agent_run_attempt_id"),
                        resultSet.getLong("first_sequence_no"),
                        resultSet.getLong("last_sequence_no"),
                        resultSet.getLong("event_count"),
                        resultSet.getString("canonical_events_sha256"),
                        resultSet.getString("object_uri"),
                        resultSet.getString("object_version"),
                        resultSet.getString("object_sha256"),
                        resultSet.getString("terminal_event_id"),
                        resultSet.getString("terminal_event_sha256"),
                        resultSet.getString("agent_execution_manifest_id"),
                        resultSet.getString("agent_execution_manifest_sha256"),
                        resultSet.getString("schema_version"),
                        resultSet.getString("authority_scope"),
                        resultSet.getBoolean("formal_business_authority"),
                        resultSet.getString("created_by")),
                expected.manifestId());
        if (stored.size() != 1 || !stored.getFirst().equals(expected)) {
            throw new IllegalStateException(
                    "archive manifest identity is bound to different immutable content");
        }
    }

    private void persistOrVerifyReceipt(StoredReceipt expected) {
        jdbc.update(
                """
                insert into agent_run_stream_archive_receipt (
                    receipt_id, receipt_sha256, manifest_id, manifest_sha256,
                    target_partition_name, stream_protocol, agent_run_id,
                    agent_run_attempt_id, first_sequence_no, last_sequence_no,
                    event_count, canonical_events_sha256, object_version,
                    object_sha256, object_readback_sha256,
                    sequence_identity_validation_json,
                    audience_cursor_validation_json, delivery_high_watermark,
                    hot_retention_started_at, hot_retention_eligible_at,
                    receipt_status, verified_at, verified_by
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          cast(? as jsonb), cast(? as jsonb), ?, ?, ?, 'VERIFIED', ?, ?)
                on conflict (receipt_id) do nothing
                """,
                expected.receiptId(), expected.receiptHash(), expected.manifestId(),
                expected.manifestHash(), expected.targetPartitionName(),
                expected.streamProtocol(), expected.runId(), expected.attemptId(),
                expected.firstSequenceNo(), expected.lastSequenceNo(), expected.eventCount(),
                expected.canonicalEventsHash(), expected.objectVersion(), expected.objectHash(),
                expected.objectReadbackHash(), expected.sequenceValidationJson(),
                expected.audienceValidationJson(), expected.deliveryHighWatermark(),
                Timestamp.from(expected.hotRetentionStartedAt()),
                Timestamp.from(expected.hotRetentionEligibleAt()),
                Timestamp.from(expected.verifiedAt()), expected.verifiedBy());
        List<StoredReceipt> stored = jdbc.query(
                """
                select receipt_id, receipt_sha256, manifest_id, manifest_sha256,
                       target_partition_name, stream_protocol, agent_run_id,
                       agent_run_attempt_id, first_sequence_no, last_sequence_no,
                       event_count, canonical_events_sha256, object_version,
                       object_sha256, object_readback_sha256,
                       sequence_identity_validation_json::text,
                       audience_cursor_validation_json::text,
                       delivery_high_watermark, hot_retention_started_at,
                       hot_retention_eligible_at, receipt_status, authority_scope,
                       formal_business_authority, verified_at, verified_by
                  from agent_run_stream_archive_receipt where receipt_id = ?
                """,
                (resultSet, rowNumber) -> new StoredReceipt(
                        resultSet.getString("receipt_id"),
                        resultSet.getString("receipt_sha256"),
                        resultSet.getString("manifest_id"),
                        resultSet.getString("manifest_sha256"),
                        resultSet.getString("target_partition_name"),
                        resultSet.getString("stream_protocol"),
                        resultSet.getString("agent_run_id"),
                        resultSet.getString("agent_run_attempt_id"),
                        resultSet.getLong("first_sequence_no"),
                        resultSet.getLong("last_sequence_no"),
                        resultSet.getLong("event_count"),
                        resultSet.getString("canonical_events_sha256"),
                        resultSet.getString("object_version"),
                        resultSet.getString("object_sha256"),
                        resultSet.getString("object_readback_sha256"),
                        canonicalJson(resultSet.getString(
                                "sequence_identity_validation_json")),
                        canonicalJson(resultSet.getString(
                                "audience_cursor_validation_json")),
                        resultSet.getLong("delivery_high_watermark"),
                        resultSet.getTimestamp("hot_retention_started_at").toInstant(),
                        resultSet.getTimestamp("hot_retention_eligible_at").toInstant(),
                        resultSet.getString("receipt_status"),
                        resultSet.getString("authority_scope"),
                        resultSet.getBoolean("formal_business_authority"),
                        resultSet.getTimestamp("verified_at").toInstant(),
                        resultSet.getString("verified_by")),
                expected.receiptId());
        if (stored.size() != 1 || !stored.getFirst().equals(expected)) {
            throw new IllegalStateException(
                    "archive receipt identity is bound to different immutable content");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("archive evidence cannot be encoded", exception);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("archive evidence JSON cannot be decoded", exception);
        }
    }

    private String canonicalJson(String value) {
        return ContractJson.canonicalString(readJson(value));
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalStateException("archive validation is missing " + field);
        }
        return value;
    }

    private static boolean isTerminal(String eventType) {
        return "final".equals(eventType)
                || "error".equals(eventType)
                || "attempt_aborted".equals(eventType);
    }

    public record ArchiveRequest(
            String manifestId,
            String receiptId,
            String targetPartitionName,
            Instant partitionRangeStart,
            Instant partitionRangeEnd,
            String streamProtocol,
            String runId,
            String attemptId,
            String objectUri,
            String objectVersion,
            String objectHash,
            String objectReadbackHash,
            String objectCreationReceiptId,
            String objectCreationReceiptHash,
            Instant verifiedAt,
            String verifiedBy,
            String createdBy) {

        public ArchiveRequest {
            partitionRangeStart = micros(partitionRangeStart);
            partitionRangeEnd = micros(partitionRangeEnd);
            verifiedAt = micros(verifiedAt);
        }

        ArchiveRequest requireValid() {
            required(manifestId, "manifestId");
            required(receiptId, "receiptId");
            required(targetPartitionName, "targetPartitionName");
            Objects.requireNonNull(partitionRangeStart, "partitionRangeStart");
            Objects.requireNonNull(partitionRangeEnd, "partitionRangeEnd");
            if (!partitionRangeEnd.isAfter(partitionRangeStart)) {
                throw new IllegalArgumentException("partition range must be increasing");
            }
            if (!"agent-stream.v2".equals(streamProtocol)) {
                throw new IllegalArgumentException(
                        "archive store currently requires exact AgentRun V2 identity");
            }
            required(runId, "runId");
            required(attemptId, "attemptId");
            if (objectUri == null || !objectUri.matches("^(s3|minio|urn):.+")) {
                throw new IllegalArgumentException(
                        "objectUri must use an approved immutable scheme");
            }
            required(objectVersion, "objectVersion");
            sha256(objectHash, "objectHash");
            sha256(objectReadbackHash, "objectReadbackHash");
            if (!objectHash.equals(objectReadbackHash)) {
                throw new IllegalArgumentException("archive object readback hash must match");
            }
            required(objectCreationReceiptId, "objectCreationReceiptId");
            sha256(objectCreationReceiptHash, "objectCreationReceiptHash");
            Objects.requireNonNull(verifiedAt, "verifiedAt");
            required(verifiedBy, "verifiedBy");
            required(createdBy, "createdBy");
            return this;
        }
    }

    private record FormalBinding(
            long terminalSequenceNo,
            String terminalEventId,
            String terminalPayloadHash,
            String executionManifestId,
            String executionManifestHash,
            Instant finalizedAt,
            boolean terminalEventRetained,
            boolean immutableManifestRetained) {}

    private record ArchivedEventIdentity(
            String eventId,
            long sequenceNo,
            String eventType,
            String payloadHash,
            Instant recordedAt,
            String targetPartitionName) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record ManifestDocument(
            String schemaVersion,
            String manifestId,
            String targetPartitionName,
            Instant partitionRangeStart,
            Instant partitionRangeEnd,
            String streamProtocol,
            String runId,
            String attemptId,
            long firstSequenceNo,
            long lastSequenceNo,
            long eventCount,
            String canonicalEventsHash,
            String objectUri,
            String objectVersion,
            String objectHash,
            String terminalEventId,
            String terminalPayloadHash,
            String executionManifestId,
            String executionManifestHash,
            String objectCreationReceiptId,
            String objectCreationReceiptHash,
            String authorityScope,
            boolean formalBusinessAuthority,
            String createdBy) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record ReceiptDocument(
            String schemaVersion,
            String receiptId,
            String manifestId,
            String manifestHash,
            String targetPartitionName,
            String streamProtocol,
            String runId,
            String attemptId,
            long firstSequenceNo,
            long lastSequenceNo,
            long eventCount,
            String canonicalEventsHash,
            String objectVersion,
            String objectHash,
            String objectReadbackHash,
            String sequenceValidationHash,
            String audienceValidationHash,
            long deliveryHighWatermark,
            Instant hotRetentionStartedAt,
            Instant hotRetentionEligibleAt,
            Instant verifiedAt,
            String verifiedBy,
            String authorityScope,
            boolean formalBusinessAuthority,
            boolean releaseEvidenceComplete) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record SequenceValidationDocument(
            String schemaVersion,
            String status,
            boolean sequenceContiguous,
            boolean eventIdentityExact,
            String objectCreationReceiptId,
            String objectCreationReceiptSha256,
            String compatibilityReportSha256,
            long sourceEventCount,
            long targetEventCount,
            boolean releaseEvidenceComplete) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record AudienceValidationDocument(
            String schemaVersion,
            String status,
            boolean audienceParity,
            boolean actorIdParity,
            boolean cursorParity,
            String compatibilityReportSha256,
            boolean releaseEvidenceComplete) {}

    private record StoredManifest(
            String manifestId,
            String manifestHash,
            String targetPartitionName,
            Instant partitionRangeStart,
            Instant partitionRangeEnd,
            String streamProtocol,
            String runId,
            String attemptId,
            long firstSequenceNo,
            long lastSequenceNo,
            long eventCount,
            String canonicalEventsHash,
            String objectUri,
            String objectVersion,
            String objectHash,
            String terminalEventId,
            String terminalPayloadHash,
            String executionManifestId,
            String executionManifestHash,
            String schemaVersion,
            String authorityScope,
            boolean formalBusinessAuthority,
            String createdBy) {}

    private record StoredReceipt(
            String receiptId,
            String receiptHash,
            String manifestId,
            String manifestHash,
            String targetPartitionName,
            String streamProtocol,
            String runId,
            String attemptId,
            long firstSequenceNo,
            long lastSequenceNo,
            long eventCount,
            String canonicalEventsHash,
            String objectVersion,
            String objectHash,
            String objectReadbackHash,
            String sequenceValidationJson,
            String audienceValidationJson,
            long deliveryHighWatermark,
            Instant hotRetentionStartedAt,
            Instant hotRetentionEligibleAt,
            String receiptStatus,
            String authorityScope,
            boolean formalBusinessAuthority,
            Instant verifiedAt,
            String verifiedBy) {}

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }

    private static Instant micros(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }
}

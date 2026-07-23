package com.example.dispute.evidence.infrastructure.persistence;

import com.example.dispute.evidence.application.graph.EvidenceAssetAuthorization.ActualLoadReceipt;
import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot;
import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot.GraphLeaseAuthority;
import com.example.dispute.evidence.application.graph.EvidenceCurrentAuthoritySnapshot.GraphLeaseRequirement;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceipt;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceipt.BatchMergeBinding;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceiptLookup;
import com.example.dispute.evidence.application.graph.EvidenceTerminalSummary;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceFinalizationReceiptRef;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionAdapter;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionQuery;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.TerminalProposal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL receipt ledger. It is deliberately unregistered while formal Evidence is closed. */
public final class JdbcEvidenceFinalizationReceiptLedger
    implements EvidenceFinalizationLedger,
        EvidenceFinalizationReceiptLookup,
        EvidenceProcessProjectionQuery.StateEnricher {

  private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
  private static final String RECEIPT_COLUMNS =
      """
      schema_version, receipt_id, receipt_hash, operation_type, operation_key,
      request_hash, result_hash, commit_scope, status, formal_domain_write,
      formal_sink_eligible, tenant_surrogate, case_id, room_epoch, fencing_token,
      source_revision, process_revision, room_revision, operation_binding_json,
      merge_count, domain_event_ids_json, outbox_ids_json, hearing_opened, committed_at,
      committed_at_epoch_second, committed_at_nano
      """;
  private static final String SUMMARY_COLUMNS =
      """
      schema_version, summary_hash, receipt_id, receipt_hash, tenant_surrogate, case_id,
      room_epoch, java_room_fencing_token, graph_lease_fencing_token,
      java_finalization_fencing_token, source_revision, process_revision, room_revision,
      authority_snapshot_hash, graph_thread_id, manifest_hash, proposal_hash, result_hash,
      current_fact_ids_json, current_source_refs_json, committed_at,
      committed_at_epoch_second, committed_at_nano
      """;
  private static final String LOAD_COLUMNS =
      """
      load_receipt.receipt_id, load_receipt.receipt_hash, load_receipt.capability_id,
      load_receipt.capability_hash, load_receipt.capability_nonce,
      load_receipt.manifest_id, load_receipt.manifest_hash, load_receipt.evidence_id,
      load_receipt.item_hash, load_receipt.object_ref, load_receipt.immutable_object_version,
      load_receipt.object_sha256, load_receipt.content_type, load_receipt.byte_size,
      load_receipt.java_room_fencing_token, load_receipt.graph_lease_fencing_token,
      load_receipt.load_status, load_receipt.loaded_modalities_json, load_receipt.loaded_at
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final GraphLeaseAuthority graphLeaseAuthority;

  public JdbcEvidenceFinalizationReceiptLedger(
      NamedParameterJdbcTemplate jdbc,
      TransactionTemplate transactions,
      GraphLeaseAuthority graphLeaseAuthority) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.graphLeaseAuthority = Objects.requireNonNull(graphLeaseAuthority, "graphLeaseAuthority");
  }

  @Override
  public Optional<EvidenceFinalizationReceipt> findCommitted(Lookup lookup) {
    Objects.requireNonNull(lookup, "lookup");
    return exactlyOneOrEmpty(
        jdbc.query(
            "select %s from case_evidence_finalization_receipt where tenant_surrogate = :tenantSurrogate and operation_key = :operationKey"
                .formatted(RECEIPT_COLUMNS),
            Map.of(
                "tenantSurrogate", lookup.tenantSurrogate(),
                "operationKey", lookup.operationKey()),
            JdbcEvidenceFinalizationReceiptLedger::mapReceipt),
        "multiple committed receipts share one semantic operation");
  }

  @Override
  public Optional<CommittedFinalization> findExact(EvidenceFinalizationReceiptRef reference) {
    Objects.requireNonNull(reference, "reference");
    Optional<EvidenceFinalizationReceipt> receipt =
        findCommitted(new Lookup(reference.tenantSurrogate(), reference.operationKey()));
    if (receipt.isEmpty()) {
      rejectForeignIdentity(reference);
      return Optional.empty();
    }
    CommittedFinalization result = hydrate(receipt.orElseThrow());
    try {
      result.requireReference(reference);
    } catch (ReceiptReferenceRejectedException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new ReceiptReferenceRejectedException(
          Rejection.CORRUPT, "committed receipt projection is invalid", failure);
    }
    return Optional.of(result);
  }

  @Override
  public Optional<CommittedFinalization> findForActivity(
      EvidenceActivityProtocol.ActivityRequest request) {
    Objects.requireNonNull(request, "request");
    Optional<EvidenceFinalizationReceipt> receipt =
        findCommitted(new Lookup(request.tenantSurrogate(), request.operationKey()));
    if (receipt.isEmpty()) {
      return Optional.empty();
    }
    EvidenceFinalizationReceipt committed = receipt.orElseThrow();
    EvidenceFinalizationLedger.requireExactReplay(
        committed, request.operationKey(), request.requestHash());
    CommittedFinalization result = hydrate(committed);
    result.requireActivity(request);
    return Optional.of(result);
  }

  @Override
  public EvidenceProcessProjectionAdapter.ProjectionEvidenceState enrich(
      EvidenceProcessProjectionAdapter.ProjectionRow row,
      AuthenticatedActor actor,
      EvidenceProcessProjectionAdapter.ProjectionEvidenceState current) {
    Objects.requireNonNull(row, "row");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(current, "current");
    if (!"SHADOW".equals(row.writerMode())
        || row.tenantSurrogate() == null
        || !row.tenantSurrogate().startsWith("TENANT_P5_SYNTHETIC_")
        || row.roomRevisionValue() == null) {
      return current;
    }
    List<EvidenceTerminalSummary> summaries =
        jdbc.query(
            """
            select %s from case_evidence_terminal_summary
             where tenant_surrogate = :tenantSurrogate
               and case_id = :caseId
               and room_epoch = :roomEpoch
               and java_room_fencing_token = :javaRoomFencingToken
               and process_revision = :processRevision
               and room_revision = :roomRevision
            """
                .formatted(SUMMARY_COLUMNS),
            new MapSqlParameterSource()
                .addValue("tenantSurrogate", row.tenantSurrogate())
                .addValue("caseId", row.caseId())
                .addValue("roomEpoch", row.projectionRoomEpoch())
                .addValue("javaRoomFencingToken", row.projectionFencingToken())
                .addValue("processRevision", row.projectionProcessRevision())
                .addValue("roomRevision", row.roomRevisionValue()),
            JdbcEvidenceFinalizationReceiptLedger::mapSummary);
    Optional<EvidenceTerminalSummary> summary =
        exactlyOneOrEmpty(summaries, "multiple terminal summaries match one projection epoch");
    if (summary.isEmpty()) {
      return current;
    }
    EvidenceTerminalSummary terminal = summary.orElseThrow();
    EvidenceFinalizationReceipt receipt = findReceiptById(terminal.receiptId());
    CommittedFinalization committed = new CommittedFinalization(receipt, terminal);
    BatchMergeBinding binding = (BatchMergeBinding) committed.receipt().operationBinding();
    TerminalProposal proposal = new TerminalProposal(receipt.receiptId(), terminal.proposalHash());
    if (current.terminalProposal() != null && !current.terminalProposal().equals(proposal)) {
      throw new ReceiptReferenceRejectedException(
          Rejection.CONFLICTING, "durable terminal proposal conflicts with projection state");
    }
    return new EvidenceProcessProjectionAdapter.ProjectionEvidenceState(
        current.originalDeadlineAt(),
        current.warningSent(),
        current.warningSentAt(),
        current.partyCompletion(),
        current.assessmentCounts(),
        binding.dossierTargetVersion(),
        current.lastEventSequence(),
        current.terminalReason(),
        proposal,
        current.recovery());
  }

  private CommittedFinalization hydrate(EvidenceFinalizationReceipt receipt) {
    EvidenceTerminalSummary summary =
        exactlyOneOrEmpty(
                jdbc.query(
                    "select %s from case_evidence_terminal_summary where receipt_id = :receiptId"
                        .formatted(SUMMARY_COLUMNS),
                    Map.of("receiptId", receipt.receiptId()),
                    JdbcEvidenceFinalizationReceiptLedger::mapSummary),
                "multiple terminal summaries share one receipt")
            .orElseThrow(
                () ->
                    new ReceiptReferenceRejectedException(
                        Rejection.CORRUPT, "committed receipt has no terminal summary"));
    try {
      return new CommittedFinalization(receipt, summary);
    } catch (RuntimeException failure) {
      throw new ReceiptReferenceRejectedException(
          Rejection.CORRUPT, "committed receipt projection is invalid", failure);
    }
  }

  private EvidenceFinalizationReceipt findReceiptById(String receiptId) {
    return exactlyOneOrEmpty(
            jdbc.query(
                "select %s from case_evidence_finalization_receipt where receipt_id = :receiptId"
                    .formatted(RECEIPT_COLUMNS),
                Map.of("receiptId", receiptId),
                JdbcEvidenceFinalizationReceiptLedger::mapReceipt),
            "multiple finalization receipts share one receipt id")
        .orElseThrow(
            () ->
                new ReceiptReferenceRejectedException(
                    Rejection.CORRUPT, "terminal summary receipt is missing"));
  }

  @Override
  public EvidenceFinalizationReceipt commitOrReplay(CommitRequest request) {
    Objects.requireNonNull(request, "request");
    EvidenceFinalizationReceipt committed =
        transactions.execute(
            ignored -> {
              EvidenceFinalizationReceipt candidate = request.candidate();
              Optional<EvidenceFinalizationReceipt> replay = findCommitted(candidate);
              if (replay.isPresent()) {
                return exactReplay(replay.orElseThrow(), candidate);
              }

              lockSemanticOperation(candidate);
              replay = findCommitted(candidate);
              if (replay.isPresent()) {
                return exactReplay(replay.orElseThrow(), candidate);
              }

              EvidenceCurrentAuthoritySnapshot authority =
                  lockCurrentAuthority(request.authorityRequirement());
              if (!authority.exactlyMatches(request.authorityRequirement())) {
                throw rejected("EVIDENCE_AUTHORITY_CHANGED_BEFORE_COMMIT");
              }
              ValidatedLoads validatedLoads =
                  validateActualLoadReceipts(request.authorityRequirement(), authority);
              requireCurrentGraphLease(candidate, authority, validatedLoads);
              long finalizationFence = nextFinalizationFence();
              EvidenceTerminalSummary summary =
                  EvidenceTerminalSummary.create(
                      candidate, authority, validatedLoads.graphLeaseFencingToken(), finalizationFence);
              insertReceipt(candidate, authority);
              insertLoadBindings(candidate, validatedLoads.receipts());
              insertSummary(summary);
              return candidate;
            });
    return Objects.requireNonNull(committed, "receipt transaction returned no result");
  }

  private Optional<EvidenceFinalizationReceipt> findCommitted(EvidenceFinalizationReceipt value) {
    return findCommitted(new Lookup(value.tenantSurrogate(), value.operationKey()));
  }

  private static EvidenceFinalizationReceipt exactReplay(
      EvidenceFinalizationReceipt existing, EvidenceFinalizationReceipt candidate) {
    EvidenceFinalizationLedger.requireExactReplay(
        existing, candidate.operationKey(), candidate.requestHash());
    return existing;
  }

  private void rejectForeignIdentity(EvidenceFinalizationReceiptRef reference) {
    List<EvidenceFinalizationReceipt> conflicts =
        jdbc.query(
            """
            select %s from case_evidence_finalization_receipt
             where receipt_id = :receiptId
                or receipt_hash = :receiptHash
                or operation_key = :operationKey
            """
                .formatted(RECEIPT_COLUMNS),
            new MapSqlParameterSource()
                .addValue("receiptId", reference.receiptId())
                .addValue("receiptHash", reference.receiptHash())
                .addValue("operationKey", reference.operationKey()),
            JdbcEvidenceFinalizationReceiptLedger::mapReceipt);
    if (!conflicts.isEmpty()) {
      throw new ReceiptReferenceRejectedException(
          Rejection.FOREIGN, "receipt identity belongs to another authority scope");
    }
  }

  private void lockSemanticOperation(EvidenceFinalizationReceipt candidate) {
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(:semanticKey, 0))",
        Map.of("semanticKey", candidate.tenantSurrogate() + ":" + candidate.operationKey()),
        Object.class);
  }

  private EvidenceCurrentAuthoritySnapshot lockCurrentAuthority(
      AuthorityRequirement requirement) {
    List<EvidenceCurrentAuthoritySnapshot> rows =
        jdbc.query(
            """
            select authority_snapshot_hash, graph_binding_id, runtime_mode, agent_profile_id, tenant_surrogate,
                   case_id, room_id, room_epoch, java_room_fencing_token, actor_id, actor_role,
                   participant_id, actor_scope_hash, agent_session_id, source_revision,
                   process_revision, room_revision, current_fact_ids_json,
                   current_source_refs_json
              from case_evidence_current_authority_snapshot
             where tenant_surrogate = :tenantSurrogate
               and case_id = :caseId
               and room_id = :roomId
               and room_epoch = :roomEpoch
               and is_current
             for update
            """,
            authorityParameters(requirement),
            JdbcEvidenceFinalizationReceiptLedger::mapAuthority);
    if (rows.size() != 1) {
      throw rejected("EVIDENCE_CURRENT_AUTHORITY_MISSING_OR_AMBIGUOUS");
    }
    return rows.getFirst();
  }

  private ValidatedLoads validateActualLoadReceipts(
      AuthorityRequirement requirement, EvidenceCurrentAuthoritySnapshot authority) {
    if (requirement.actualLoadRequirements().isEmpty()) {
      throw rejected("EVIDENCE_TERMINAL_GRAPH_LEASE_NOT_PROVEN");
    }
    Set<Long> graphLeaseFences = new LinkedHashSet<>();
    List<ActualLoadReceipt> validatedReceipts = new ArrayList<>();
    for (ActualLoadRequirement expected : requirement.actualLoadRequirements()) {
      List<ActualLoadReceipt> rows =
          jdbc.query(
              """
              select %s
                from case_evidence_asset_load_receipt load_receipt
                join case_evidence_graph_binding graph_binding
                  on graph_binding.binding_id = load_receipt.graph_binding_id
                join case_evidence_current_authority_snapshot authority
                  on authority.graph_binding_id = graph_binding.binding_id
                 and authority.authority_snapshot_hash = :authoritySnapshotHash
               where graph_binding.tenant_surrogate = :tenantSurrogate
                 and graph_binding.case_id = :caseId
                 and graph_binding.room_epoch = :roomEpoch
                 and load_receipt.receipt_id = :receiptId
                 and load_receipt.receipt_hash = :receiptHash
               for key share of load_receipt, graph_binding
              """
                  .formatted(LOAD_COLUMNS),
              new MapSqlParameterSource()
                  .addValue("authoritySnapshotHash", authority.authoritySnapshotHash())
                  .addValue("tenantSurrogate", authority.tenantSurrogate())
                  .addValue("caseId", authority.caseId())
                  .addValue("roomEpoch", authority.roomEpoch())
                  .addValue("receiptId", expected.receiptId())
                  .addValue("receiptHash", expected.receiptHash()),
              JdbcEvidenceFinalizationReceiptLedger::mapActualLoadReceipt);
      if (rows.size() != 1) {
        throw rejected("EVIDENCE_ACTUAL_LOAD_RECEIPT_NOT_AUTHORIZED");
      }
      ActualLoadReceipt actual = rows.getFirst();
      if (!actual.evidenceId().equals(expected.evidenceId())
          || !actual.itemHash().equals(expected.itemHash())
          || !actual.receiptId().equals(expected.receiptId())
          || !actual.receiptHash().equals(expected.receiptHash())
          || !actual.manifestHash().equals(expected.manifestHash())
          || actual.javaRoomFencingToken() != expected.javaRoomFencingToken()
          || actual.javaRoomFencingToken() != authority.javaRoomFencingToken()
          || !"LOADED".equals(actual.loadStatus())) {
        throw rejected("EVIDENCE_ACTUAL_LOAD_RECEIPT_CONFLICT");
      }
      graphLeaseFences.add(actual.graphLeaseFencingToken());
      validatedReceipts.add(actual);
    }
    if (graphLeaseFences.size() != 1) {
      throw rejected("EVIDENCE_GRAPH_LEASE_FENCE_MIXED_OR_STALE");
    }
    return new ValidatedLoads(graphLeaseFences.iterator().next(), validatedReceipts);
  }

  private long nextFinalizationFence() {
    Long value =
        jdbc.queryForObject(
            "select nextval('case_evidence_finalization_fencing_token_seq')",
            Map.of(),
            Long.class);
    if (value == null || value < 1) {
      throw rejected("EVIDENCE_FINALIZATION_FENCE_NOT_ALLOCATED");
    }
    return value;
  }

  private void requireCurrentGraphLease(
      EvidenceFinalizationReceipt candidate,
      EvidenceCurrentAuthoritySnapshot authority,
      ValidatedLoads validatedLoads) {
    if (!(candidate.operationBinding() instanceof BatchMergeBinding binding)) {
      throw rejected("EVIDENCE_GRAPH_LEASE_OPERATION_BINDING_INVALID");
    }
    graphLeaseAuthority.requireCurrent(
        new GraphLeaseRequirement(
            authority.authoritySnapshotHash(),
            authority.tenantSurrogate(),
            authority.caseId(),
            authority.roomId(),
            authority.roomEpoch(),
            authority.javaRoomFencingToken(),
            binding.threadId(),
            validatedLoads.graphLeaseFencingToken()));
  }

  private void insertReceipt(
      EvidenceFinalizationReceipt receipt, EvidenceCurrentAuthoritySnapshot authority) {
    int inserted =
        jdbc.update(
            """
            insert into case_evidence_finalization_receipt (
                schema_version, receipt_id, receipt_hash, operation_type, operation_key,
                request_hash, result_hash, commit_scope, status, formal_domain_write,
                formal_sink_eligible, tenant_surrogate, case_id, room_id, graph_binding_id,
                room_epoch, fencing_token,
                source_revision, process_revision, room_revision, operation_binding_json,
                merge_count, domain_event_ids_json, outbox_ids_json, hearing_opened,
                committed_at, committed_at_epoch_second, committed_at_nano,
                authority_snapshot_hash
            ) values (
                :schemaVersion, :receiptId, :receiptHash, :operationType, :operationKey,
                :requestHash, :resultHash, :commitScope, :status, :formalDomainWrite,
                :formalSinkEligible, :tenantSurrogate, :caseId, :roomId, :graphBindingId,
                :roomEpoch, :fencingToken,
                :sourceRevision, :processRevision, :roomRevision,
                cast(:operationBindingJson as jsonb), :mergeCount,
                cast(:domainEventIdsJson as jsonb), cast(:outboxIdsJson as jsonb),
                :hearingOpened, :committedAt, :committedAtEpochSecond, :committedAtNano,
                :authoritySnapshotHash
            )
            """,
            receiptParameters(receipt, authority));
    if (inserted != 1) {
      throw rejected("EVIDENCE_FINALIZATION_RECEIPT_NOT_INSERTED");
    }
  }

  private void insertSummary(EvidenceTerminalSummary summary) {
    int inserted =
        jdbc.update(
            """
            insert into case_evidence_terminal_summary (
                schema_version, summary_hash, receipt_id, receipt_hash, tenant_surrogate,
                case_id, room_epoch, java_room_fencing_token, graph_lease_fencing_token,
                java_finalization_fencing_token, source_revision, process_revision,
                room_revision, authority_snapshot_hash, graph_thread_id, manifest_hash, proposal_hash,
                result_hash, current_fact_ids_json, current_source_refs_json, committed_at,
                committed_at_epoch_second, committed_at_nano
            ) values (
                :schemaVersion, :summaryHash, :receiptId, :receiptHash, :tenantSurrogate,
                :caseId, :roomEpoch, :javaRoomFencingToken, :graphLeaseFencingToken,
                :javaFinalizationFencingToken, :sourceRevision, :processRevision,
                :roomRevision, :authoritySnapshotHash, :graphThreadId, :manifestHash, :proposalHash,
                :resultHash, cast(:currentFactIdsJson as jsonb),
                cast(:currentSourceRefsJson as jsonb), :committedAt,
                :committedAtEpochSecond, :committedAtNano
            )
            """,
            summaryParameters(summary));
    if (inserted != 1) {
      throw rejected("EVIDENCE_TERMINAL_SUMMARY_NOT_INSERTED");
    }
  }

  private void insertLoadBindings(
      EvidenceFinalizationReceipt receipt, List<ActualLoadReceipt> loadReceipts) {
    for (ActualLoadReceipt loadReceipt : loadReceipts) {
      int inserted =
          jdbc.update(
              """
              insert into case_evidence_finalization_receipt_load_binding (
                  receipt_id, receipt_hash, load_receipt_id, load_receipt_hash,
                  tenant_surrogate, case_id, room_epoch, evidence_id, item_hash,
                  manifest_hash, java_room_fencing_token, graph_lease_fencing_token,
                  bound_at
              ) values (
                  :receiptId, :receiptHash, :loadReceiptId, :loadReceiptHash,
                  :tenantSurrogate, :caseId, :roomEpoch, :evidenceId, :itemHash,
                  :manifestHash, :javaRoomFencingToken, :graphLeaseFencingToken,
                  :boundAt
              )
              """,
              new MapSqlParameterSource()
                  .addValue("receiptId", receipt.receiptId())
                  .addValue("receiptHash", receipt.receiptHash())
                  .addValue("loadReceiptId", loadReceipt.receiptId())
                  .addValue("loadReceiptHash", loadReceipt.receiptHash())
                  .addValue("tenantSurrogate", receipt.tenantSurrogate())
                  .addValue("caseId", receipt.caseId())
                  .addValue("roomEpoch", receipt.roomEpoch())
                  .addValue("evidenceId", loadReceipt.evidenceId())
                  .addValue("itemHash", loadReceipt.itemHash())
                  .addValue("manifestHash", loadReceipt.manifestHash())
                  .addValue("javaRoomFencingToken", loadReceipt.javaRoomFencingToken())
                  .addValue("graphLeaseFencingToken", loadReceipt.graphLeaseFencingToken())
                  .addValue("boundAt", receipt.committedAt().atOffset(ZoneOffset.UTC)));
      if (inserted != 1) {
        throw rejected("EVIDENCE_FINALIZATION_LOAD_BINDING_NOT_INSERTED");
      }
    }
  }

  private static MapSqlParameterSource authorityParameters(AuthorityRequirement value) {
    return new MapSqlParameterSource()
        .addValue("tenantSurrogate", value.tenantSurrogate())
        .addValue("caseId", value.caseId())
        .addValue("roomId", value.roomId())
        .addValue("roomEpoch", value.roomEpoch());
  }

  private static MapSqlParameterSource receiptParameters(
      EvidenceFinalizationReceipt value, EvidenceCurrentAuthoritySnapshot authority) {
    return new MapSqlParameterSource()
        .addValue("schemaVersion", value.schemaVersion())
        .addValue("receiptId", value.receiptId())
        .addValue("receiptHash", value.receiptHash())
        .addValue("operationType", value.operationType().name())
        .addValue("operationKey", value.operationKey())
        .addValue("requestHash", value.requestHash())
        .addValue("resultHash", value.resultHash())
        .addValue("commitScope", value.commitScope())
        .addValue("status", value.status())
        .addValue("formalDomainWrite", value.formalDomainWrite())
        .addValue("formalSinkEligible", value.formalSinkEligible())
        .addValue("tenantSurrogate", value.tenantSurrogate())
        .addValue("caseId", value.caseId())
        .addValue("roomId", authority.roomId())
        .addValue("graphBindingId", authority.graphBindingId())
        .addValue("roomEpoch", value.roomEpoch())
        .addValue("fencingToken", value.fencingToken())
        .addValue("sourceRevision", value.sourceRevision())
        .addValue("processRevision", value.processRevision())
        .addValue("roomRevision", value.roomRevision())
        .addValue("operationBindingJson", writeJson(value.operationBinding().toContractJson()))
        .addValue("mergeCount", value.mergeCount())
        .addValue("domainEventIdsJson", writeJson(value.domainEventIds()))
        .addValue("outboxIdsJson", writeJson(value.outboxIds()))
        .addValue("hearingOpened", value.hearingOpened())
        .addValue("committedAt", postgresTimestamp(value.committedAt()))
        .addValue("committedAtEpochSecond", value.committedAt().getEpochSecond())
        .addValue("committedAtNano", value.committedAt().getNano())
        .addValue("authoritySnapshotHash", authority.authoritySnapshotHash());
  }

  private static MapSqlParameterSource summaryParameters(EvidenceTerminalSummary value) {
    return new MapSqlParameterSource()
        .addValue("schemaVersion", value.schemaVersion())
        .addValue("summaryHash", value.summaryHash())
        .addValue("receiptId", value.receiptId())
        .addValue("receiptHash", value.receiptHash())
        .addValue("tenantSurrogate", value.tenantSurrogate())
        .addValue("caseId", value.caseId())
        .addValue("roomEpoch", value.roomEpoch())
        .addValue("javaRoomFencingToken", value.javaRoomFencingToken())
        .addValue("graphLeaseFencingToken", value.graphLeaseFencingToken())
        .addValue("javaFinalizationFencingToken", value.javaFinalizationFencingToken())
        .addValue("sourceRevision", value.sourceRevision())
        .addValue("processRevision", value.processRevision())
        .addValue("roomRevision", value.roomRevision())
        .addValue("authoritySnapshotHash", value.authoritySnapshotHash())
        .addValue("graphThreadId", value.graphThreadId())
        .addValue("manifestHash", value.manifestHash())
        .addValue("proposalHash", value.proposalHash())
        .addValue("resultHash", value.resultHash())
        .addValue("currentFactIdsJson", writeJson(value.currentFactIds()))
        .addValue("currentSourceRefsJson", writeJson(value.currentSourceRefs()))
        .addValue("committedAt", postgresTimestamp(value.committedAt()))
        .addValue("committedAtEpochSecond", value.committedAt().getEpochSecond())
        .addValue("committedAtNano", value.committedAt().getNano());
  }

  private static EvidenceFinalizationReceipt mapReceipt(ResultSet row, int ignored)
      throws SQLException {
    EvidenceFinalizationReceipt.OperationType operationType =
        EvidenceFinalizationReceipt.OperationType.valueOf(row.getString("operation_type"));
    JsonNode bindingJson = readTree(row.getString("operation_binding_json"));
    if (operationType != EvidenceFinalizationReceipt.OperationType.BATCH_MERGE) {
      throw new IllegalStateException("unsupported persisted Evidence finalization operation");
    }
    BatchMergeBinding binding =
        new BatchMergeBinding(
            bindingJson.required("manifest_hash").textValue(),
            bindingJson.required("dossier_target_version").longValue(),
            bindingJson.required("proposal_hash").textValue(),
            bindingJson.required("logical_run_id").textValue(),
            bindingJson.required("command_id").textValue(),
            bindingJson.required("attempt_id").textValue(),
            bindingJson.required("thread_id").textValue());
    return new EvidenceFinalizationReceipt(
        row.getString("schema_version"),
        row.getString("receipt_id"),
        row.getString("receipt_hash"),
        operationType,
        row.getString("operation_key"),
        row.getString("request_hash"),
        row.getString("result_hash"),
        row.getString("commit_scope"),
        row.getString("status"),
        row.getBoolean("formal_domain_write"),
        row.getBoolean("formal_sink_eligible"),
        row.getString("tenant_surrogate"),
        row.getString("case_id"),
        row.getLong("room_epoch"),
        row.getLong("fencing_token"),
        row.getLong("source_revision"),
        row.getLong("process_revision"),
        row.getLong("room_revision"),
        binding,
        row.getInt("merge_count"),
        readStringList(row.getString("domain_event_ids_json")),
        readStringList(row.getString("outbox_ids_json")),
        row.getBoolean("hearing_opened"),
        exactInstant(row));
  }

  private static EvidenceCurrentAuthoritySnapshot mapAuthority(ResultSet row, int ignored)
      throws SQLException {
    return new EvidenceCurrentAuthoritySnapshot(
        row.getString("authority_snapshot_hash"),
        row.getString("graph_binding_id"),
        row.getString("runtime_mode"),
        row.getString("agent_profile_id"),
        row.getString("tenant_surrogate"),
        row.getString("case_id"),
        row.getString("room_id"),
        row.getLong("room_epoch"),
        row.getLong("java_room_fencing_token"),
        row.getString("actor_id"),
        row.getString("actor_role"),
        row.getString("participant_id"),
        row.getString("actor_scope_hash"),
        row.getString("agent_session_id"),
        row.getLong("source_revision"),
        row.getLong("process_revision"),
        row.getLong("room_revision"),
        readStringList(row.getString("current_fact_ids_json")),
        readStringList(row.getString("current_source_refs_json")));
  }

  private static ActualLoadReceipt mapActualLoadReceipt(ResultSet row, int ignored)
      throws SQLException {
    return new ActualLoadReceipt(
        row.getString("receipt_id"),
        row.getString("receipt_hash"),
        row.getString("capability_id"),
        row.getString("capability_hash"),
        row.getString("capability_nonce"),
        row.getString("manifest_id"),
        row.getString("manifest_hash"),
        row.getString("evidence_id"),
        row.getString("item_hash"),
        row.getString("object_ref"),
        row.getString("immutable_object_version"),
        row.getString("object_sha256"),
        row.getString("content_type"),
        row.getLong("byte_size"),
        row.getLong("java_room_fencing_token"),
        row.getLong("graph_lease_fencing_token"),
        row.getString("load_status"),
        readStringList(row.getString("loaded_modalities_json")),
        row.getObject("loaded_at", OffsetDateTime.class).toInstant());
  }

  private static EvidenceTerminalSummary mapSummary(ResultSet row, int ignored)
      throws SQLException {
    return new EvidenceTerminalSummary(
        row.getString("schema_version"),
        row.getString("summary_hash"),
        row.getString("receipt_id"),
        row.getString("receipt_hash"),
        row.getString("tenant_surrogate"),
        row.getString("case_id"),
        row.getLong("room_epoch"),
        row.getLong("java_room_fencing_token"),
        row.getLong("graph_lease_fencing_token"),
        row.getLong("java_finalization_fencing_token"),
        row.getLong("source_revision"),
        row.getLong("process_revision"),
        row.getLong("room_revision"),
        row.getString("authority_snapshot_hash"),
        row.getString("graph_thread_id"),
        row.getString("manifest_hash"),
        row.getString("proposal_hash"),
        row.getString("result_hash"),
        readStringList(row.getString("current_fact_ids_json")),
        readStringList(row.getString("current_source_refs_json")),
        exactInstant(row));
  }

  private static Instant exactInstant(ResultSet row) throws SQLException {
    return Instant.ofEpochSecond(
        row.getLong("committed_at_epoch_second"), row.getInt("committed_at_nano"));
  }

  private static OffsetDateTime postgresTimestamp(Instant value) {
    return value.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }

  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException failure) {
      throw new IllegalArgumentException("Evidence receipt value is not serializable", failure);
    }
  }

  private static JsonNode readTree(String value) {
    try {
      return MAPPER.readTree(value);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("persisted Evidence receipt JSON is invalid", failure);
    }
  }

  private static List<String> readStringList(String value) {
    try {
      return MAPPER.readValue(value, new TypeReference<>() {});
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("persisted Evidence receipt list is invalid", failure);
    }
  }

  private static <T> Optional<T> exactlyOneOrEmpty(List<T> values, String detail) {
    if (values.size() > 1) {
      throw rejected(detail);
    }
    return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
  }

  private static IllegalStateException rejected(String detail) {
    return new IllegalStateException(detail);
  }

  private record ValidatedLoads(
      long graphLeaseFencingToken, List<ActualLoadReceipt> receipts) {
    private ValidatedLoads {
      receipts = List.copyOf(receipts);
    }
  }
}

package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.hearing.application.HearingPublicTranscriptPolicy;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageSource;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.temporal.room.hearing.HearingCommittedReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Atomically binds one formal Target Hearing receipt to its append-only public transcript facts.
 *
 * <p>The caller owns the surrounding Spring transaction. This writer never derives presentation
 * text, advances a stage, or repairs a partial replay.
 */
public final class JdbcTargetHearingPublicTranscriptCommitter {

  private static final String BINDING_SCHEMA = "hearing-public-transcript-binding.v1";
  private static final String MESSAGE_SCHEMA = "hearing-public-room-message.v1";
  private static final String EVENT_SCHEMA = "hearing-public-timeline-event.v1";
  private static final String EVENT_TYPE = "ROOM_MESSAGE_CREATED";
  private static final String CREATED_BY = HearingPublicTranscriptPolicy.SYSTEM_ACTOR;
  private static final Pattern SUFFIX = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  private static final List<String> PUBLIC_AUDIENCE =
      List.of("USER", "MERCHANT", "PLATFORM_REVIEWER", "ADMIN");

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Consumer<String> afterCommitNotifier;

  public JdbcTargetHearingPublicTranscriptCommitter(DataSource dataSource) {
    this(dataSource, new ObjectMapper(), ignored -> {});
  }

  public JdbcTargetHearingPublicTranscriptCommitter(
      DataSource dataSource, ObjectMapper mapper, Consumer<String> afterCommitNotifier) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    this.afterCommitNotifier =
        Objects.requireNonNull(afterCommitNotifier, "afterCommitNotifier");
  }

  public CommitResult commit(
      CommitMode mode,
      HearingStageReceipt receipt,
      HearingRoomStart start,
      Instant committedAt,
      List<HearingPublicTranscriptPolicy.Draft> drafts) {
    Objects.requireNonNull(receipt, "receipt");
    return commit(mode, receipt.committed(), start, committedAt, drafts);
  }

  public CommitResult commit(
      CommitMode mode,
      HearingPartyTerminalReceipt receipt,
      HearingRoomStart start,
      Instant committedAt,
      List<HearingPublicTranscriptPolicy.Draft> drafts) {
    Objects.requireNonNull(receipt, "receipt");
    return commit(mode, receipt.committed(), start, committedAt, drafts);
  }

  public CommitResult commit(
      CommitMode mode,
      HearingCommittedReceipt committed,
      HearingRoomStart start,
      Instant committedAt,
      List<HearingPublicTranscriptPolicy.Draft> drafts) {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(committed, "committed");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(committedAt, "committedAt");
    Objects.requireNonNull(drafts, "drafts");
    require(
        TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive(),
        "Target Hearing public transcript requires the caller transaction");
    require(
        committedAt.equals(committedAt.truncatedTo(ChronoUnit.MICROS)),
        "Target Hearing public transcript committedAt must be microsecond canonical");

    boolean partyTerminal =
        committed.operationType()
            == com.example.dispute.hearing.domain.HearingAuthorityCommit.OperationType.PARTY_TERMINAL;
    require(
        !partyTerminal
            || (committed.sourceStage().isPartyWait()
                && committed.sourceStage().next() == committed.stage()),
        "Target Hearing public transcript PARTY_TERMINAL stage authority drifted");
    require(
        committed.matches(start),
        "Target Hearing public transcript start/receipt authority drifted");
    Authority authority = lockAuthority(committed, start);
    requireAuthority(authority, committed, start, committedAt);
    List<ValidatedDraft> validated = validateDrafts(authority, drafts);

    if (mode == CommitMode.NEW_COMMIT) {
      require(
          bindingCount(committed.receiptId()) == 0,
          "Target Hearing public transcript NEW_COMMIT already exists");
      long messageSequence = nextSequence("room_message", "room_id", start.roomId());
      long eventSequence = nextSequence("case_timeline_event", "case_id", start.caseId());
      List<Publication> publications = new ArrayList<>();
      for (ValidatedDraft draft : validated) {
        Expected expected =
            expected(
                authority,
                committedAt,
                draft,
                messageSequence + draft.ordinal(),
                eventSequence + draft.ordinal());
        insert(expected);
        verifyReplay(expected);
        publications.add(expected.publication());
      }
      require(
          bindingCount(committed.receiptId()) == validated.size(),
          "Target Hearing public transcript insert cardinality drifted");
      registerAfterCommit(start.caseId());
      return new CommitResult(committed.receiptId(), committed.receiptHash(), publications);
    }

    List<StoredSequences> stored = storedSequences(committed.receiptId());
    require(
        stored.size() == validated.size(),
        "Target Hearing public transcript STRICT_REPLAY binding cardinality drifted");
    List<Publication> publications = new ArrayList<>();
    for (int ordinal = 0; ordinal < validated.size(); ordinal++) {
      StoredSequences sequences = stored.get(ordinal);
      require(
          sequences.ordinal() == ordinal,
          "Target Hearing public transcript STRICT_REPLAY ordinal drifted");
      Expected expected =
          expected(
              authority,
              committedAt,
              validated.get(ordinal),
              sequences.messageSequence(),
              sequences.eventSequence());
      verifyReplay(expected);
      publications.add(expected.publication());
    }
    return new CommitResult(committed.receiptId(), committed.receiptHash(), publications);
  }

  private Authority lockAuthority(HearingCommittedReceipt committed, HearingRoomStart start) {
    List<Authority> rows =
        jdbc.query(
            """
            select receipt.receipt_id, receipt.receipt_hash, receipt.operation_type,
                   receipt.operation_key, receipt.request_hash, receipt.tenant_surrogate,
                   receipt.case_id, receipt.flow_instance_id, receipt.epoch_id,
                   receipt.room_type, receipt.hearing_epoch, receipt.writer_mode,
                   receipt.fencing_token, receipt.source_stage,
                   receipt.source_stage_sequence, receipt.source_process_revision,
                   receipt.source_room_revision, receipt.stage_code, receipt.stage_sequence,
                   receipt.process_revision, receipt.room_revision, receipt.result_ref,
                   receipt.result_hash, receipt.committed_event_sequence,
                   receipt.temporal_history_event_id, receipt.committed_at,
                   room.id as room_id
              from fulfillment_dispute_case dispute
              join case_room room
                on room.case_id = dispute.id
               and room.id = ?
               and room.room_type = 'HEARING'
               and room.room_status in ('OPEN', 'WAITING', 'SEALED', 'CLOSED')
              join case_room_epoch epoch
                on epoch.id = ?
               and epoch.tenant_surrogate = ?
               and epoch.case_id = dispute.id
               and epoch.room_id = room.id
               and epoch.room_type = 'HEARING'
               and epoch.room_epoch = ?
               and epoch.writer_mode = 'TEMPORAL'
               and epoch.fencing_token = ?
              join hearing_domain_receipt receipt
                on receipt.receipt_id = ?
               and receipt.receipt_hash = ?
               and receipt.tenant_surrogate = epoch.tenant_surrogate
               and receipt.case_id = epoch.case_id
               and receipt.flow_instance_id = ?
               and receipt.epoch_id = epoch.id
               and receipt.room_type = epoch.room_type
               and receipt.hearing_epoch = epoch.room_epoch
               and receipt.writer_mode = epoch.writer_mode
               and receipt.fencing_token = epoch.fencing_token
             where dispute.id = ?
             for update of dispute, room, epoch, receipt
            """,
            JdbcTargetHearingPublicTranscriptCommitter::authority,
            start.roomId(),
            start.epochId(),
            start.tenantSurrogate(),
            start.roomEpoch(),
            start.fencingToken(),
            committed.receiptId(),
            committed.receiptHash(),
            start.flowInstanceId(),
            start.caseId());
    return one(rows, "Target Hearing public transcript authority is absent or ambiguous");
  }

  private void requireAuthority(
      Authority row,
      HearingCommittedReceipt committed,
      HearingRoomStart start,
      Instant committedAt) {
    require(
        row.receiptId().equals(committed.receiptId())
            && row.receiptHash().equals(committed.receiptHash())
            && row.operationType().equals(committed.operationType().name())
            && row.operationKey().equals(committed.operationKey())
            && row.requestHash().equals(committed.requestHash())
            && row.tenantSurrogate().equals(committed.tenantSurrogate())
            && row.caseId().equals(committed.caseId())
            && row.flowInstanceId().equals(committed.flowInstanceId())
            && row.epochId().equals(committed.epochId())
            && row.roomId().equals(start.roomId())
            && row.roomType().equals("HEARING")
            && row.hearingEpoch() == committed.roomEpoch()
            && row.writerMode().equals(committed.writerMode().name())
            && row.fencingToken() == committed.fencingToken()
            && row.sourceStage().equals(committed.sourceStage().name())
            && row.sourceStageSequence() == committed.sourceStageSequence()
            && row.sourceProcessRevision() == committed.sourceProcessRevision()
            && row.sourceRoomRevision() == committed.sourceRoomRevision()
            && row.resultStage().equals(committed.stage().name())
            && row.resultStageSequence() == committed.stageSequence()
            && row.processRevision() == committed.processRevision()
            && row.roomRevision() == committed.roomRevision()
            && row.resultRef().equals(committed.resultRef())
            && row.resultHash().equals(committed.resultHash())
            && row.committedEventSequence() == committed.committedEventSequence()
            && Objects.equals(row.temporalHistoryEventId(), committed.temporalHistoryEventId())
            && row.committedAt().equals(committedAt),
        "Target Hearing public transcript formal receipt authority drifted");
  }

  private List<ValidatedDraft> validateDrafts(
      Authority authority, List<HearingPublicTranscriptPolicy.Draft> drafts) {
    require(
        !drafts.isEmpty() && drafts.size() <= 32,
        "Target Hearing public transcript draft cardinality is invalid");
    List<ValidatedDraft> result = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (int ordinal = 0; ordinal < drafts.size(); ordinal++) {
      HearingPublicTranscriptPolicy.Draft draft =
          Objects.requireNonNull(drafts.get(ordinal), "draft");
      String stage = draft.stage().name();
      require(
          stage.equals(authority.sourceStage()) || stage.equals(authority.resultStage()),
          "Target Hearing public transcript draft stage is outside the formal receipt");
      require(
          SUFFIX.matcher(draft.suffix()).matches(),
          "Target Hearing public transcript draft suffix is invalid");
      require(
          draft.text().length() <= 1_000_000,
          "Target Hearing public transcript message text is too large");
      String publicationKey = "hearing-v2:" + draft.stageSequence() + ':' + draft.suffix();
      require(
          keys.add(publicationKey),
          "Target Hearing public transcript publication key is duplicated");
      validateMessageMetadata(draft, authority);
      if (draft.agentRunId() != null) {
        require(
            draft.agentRunId().length() <= 64,
            "Target Hearing public transcript AgentRun id is too long");
        List<String> runs =
            jdbc.query(
                """
                select id from agent_run
                 where id = ? and case_id = ? and workflow_id = ?
                 for update
                """,
                (row, ignored) -> row.getString(1),
                draft.agentRunId(),
                authority.caseId(),
                authority.roomId());
        one(runs, "Target Hearing public transcript AgentRun authority is absent or ambiguous");
      }
      result.add(new ValidatedDraft(ordinal, publicationKey, draft));
    }
    return List.copyOf(result);
  }

  private static void validateMessageMetadata(
      HearingPublicTranscriptPolicy.Draft draft, Authority authority) {
    if (draft.senderType() == MessageSenderType.SYSTEM) {
      require(
          draft.senderRole().equals("SYSTEM")
              && draft.senderId().equals(HearingPublicTranscriptPolicy.SYSTEM_ACTOR)
              && draft.messageSource() == MessageSource.SYSTEM_STAGE_EVENT
              && draft.messageType() == MessageType.SYSTEM_STAGE_EVENT
              && draft.agentRunId() == null,
          "Target Hearing public transcript SYSTEM message metadata drifted");
      return;
    }
    require(
        draft.senderType() == MessageSenderType.AGENT,
        "Target Hearing public transcript sender type is not public-formal");
    Presentation presentation = presentation(draft.stage(), draft.messageSource());
    require(
        draft.senderRole().equals(presentation.role())
            && draft.senderId().equals(presentation.senderId())
            && draft.messageType() == presentation.messageType(),
        "Target Hearing public transcript Agent message metadata drifted");
    if (draft.messageSource() == MessageSource.AGENT_LLM) {
      require(
          draft.agentRunId() != null && draft.stage().name().equals(authority.sourceStage()),
          "Target Hearing public transcript AgentRun binding drifted");
    } else {
      require(
          draft.messageSource() == MessageSource.ROLE_TEMPLATE && draft.agentRunId() == null,
          "Target Hearing public transcript template AgentRun binding drifted");
    }
  }

  private static Presentation presentation(HearingFlowStage stage, MessageSource source) {
    if (source == MessageSource.ROLE_TEMPLATE) {
      return switch (stage) {
        case COURT_PREPARING ->
            new Presentation("PRESIDING_JUDGE", "presiding-judge-template", MessageType.AGENT_MESSAGE);
        case CASE_INTRODUCTION ->
            new Presentation("INTAKE_OFFICER", "intake-officer-template", MessageType.AGENT_MESSAGE);
        case EVIDENCE_INTRODUCTION ->
            new Presentation("EVIDENCE_CLERK", "evidence-clerk-template", MessageType.AGENT_MESSAGE);
        default -> throw new IllegalStateException(
            "Target Hearing public transcript template stage is invalid");
      };
    }
    require(
        source == MessageSource.AGENT_LLM,
        "Target Hearing public transcript Agent source is invalid");
    return switch (stage) {
      case INTAKE_QUESTIONS_GENERATING, INTAKE_SYNTHESIZING ->
          new Presentation("INTAKE_OFFICER", "intake_officer", MessageType.AGENT_MESSAGE);
      case EVIDENCE_REQUESTS_GENERATING, EVIDENCE_SYNTHESIZING ->
          new Presentation("EVIDENCE_CLERK", "evidence_clerk", MessageType.AGENT_MESSAGE);
      case JUDGE_V1_GENERATING, JUDGE_V2_GENERATING ->
          new Presentation("PRESIDING_JUDGE", "presiding_judge", MessageType.AGENT_MESSAGE);
      case JURY_REVIEWING ->
          new Presentation("JURY_PANEL", "jury_panel", MessageType.JURY_REVIEW_REPORT);
      default -> throw new IllegalStateException(
          "Target Hearing public transcript Agent stage is invalid");
    };
  }

  private Expected expected(
      Authority authority,
      Instant committedAt,
      ValidatedDraft validated,
      long messageSequence,
      long eventSequence) {
    HearingPublicTranscriptPolicy.Draft draft = validated.draft();
    String seed = authority.receiptId() + ':' + validated.ordinal() + ':' + validated.publicationKey();
    String messageId = "MESSAGE_HEARING_" + stable(seed + ":message", 32);
    String eventId = "EVENT_HEARING_" + stable(seed + ":event", 32);
    String bindingId = "HPTB_" + stable(seed + ":binding", 40);
    String eventKey = "hearing-public:" + authority.receiptId() + ':' + validated.ordinal();
    String traceId = "TRACE_HEARING_PUBLIC_" + authority.receiptHash().substring(0, 32)
        + '_' + validated.ordinal();

    ArrayNode audience = mapper.valueToTree(PUBLIC_AUDIENCE);
    ArrayNode empty = mapper.createArrayNode();
    ObjectNode message = mapper.createObjectNode();
    message.put("schema_version", MESSAGE_SCHEMA);
    message.put("id", messageId);
    message.put("case_id", authority.caseId());
    message.put("room_id", authority.roomId());
    message.put("sequence_no", messageSequence);
    message.put("sender_type", draft.senderType().name());
    message.put("sender_role", draft.senderRole());
    message.put("sender_id", draft.senderId());
    message.set("audience", audience.deepCopy());
    message.set("audience_actor_ids", empty.deepCopy());
    message.put("message_source", draft.messageSource().name());
    message.put("message_type", draft.messageType().name());
    message.put("message_text", draft.text());
    message.set("attachment_refs", empty.deepCopy());
    if (draft.agentRunId() == null) message.putNull("agent_run_id");
    else message.put("agent_run_id", draft.agentRunId());
    message.putNull("hearing_round");
    message.put("idempotency_key", validated.publicationKey());
    message.put("created_at", committedAt.toString());
    message.put("trace_id", traceId);
    message.put("created_by", draft.senderId());
    String messageSha = ContractJson.sha256Hex(message);

    ArrayNode sourceRefs = mapper.createArrayNode().add(authority.receiptId()).add(messageId);
    ObjectNode eventPayload = mapper.createObjectNode();
    eventPayload.put("schema_version", EVENT_SCHEMA);
    eventPayload.put("receipt_id", authority.receiptId());
    eventPayload.put("receipt_hash", authority.receiptHash());
    eventPayload.put("flow_instance_id", authority.flowInstanceId());
    eventPayload.put("epoch_id", authority.epochId());
    eventPayload.put("hearing_epoch", authority.hearingEpoch());
    eventPayload.put("fencing_token", authority.fencingToken());
    eventPayload.put("source_stage", authority.sourceStage());
    eventPayload.put("source_stage_sequence", authority.sourceStageSequence());
    eventPayload.put("result_stage", authority.resultStage());
    eventPayload.put("result_stage_sequence", authority.resultStageSequence());
    eventPayload.put("message_stage", draft.stage().name());
    eventPayload.put("message_stage_sequence", draft.stageSequence());
    eventPayload.put("ordinal", validated.ordinal());
    eventPayload.put("publication_key", validated.publicationKey());
    eventPayload.put("message_id", messageId);
    eventPayload.put("message_sha256", messageSha);

    ObjectNode event = mapper.createObjectNode();
    event.put("schema_version", EVENT_SCHEMA);
    event.put("id", eventId);
    event.put("case_id", authority.caseId());
    event.put("room_id", authority.roomId());
    event.put("sequence_no", eventSequence);
    event.put("event_type", EVENT_TYPE);
    event.put("event_time", committedAt.toString());
    event.set("source_refs", sourceRefs.deepCopy());
    event.set("event", eventPayload.deepCopy());
    event.set("audience", audience.deepCopy());
    event.set("audience_actor_ids", empty.deepCopy());
    event.put("event_key", eventKey);
    event.put("created_at", committedAt.toString());
    event.put("created_by", CREATED_BY);
    String eventSha = ContractJson.sha256Hex(event);

    ObjectNode binding = bindingDocument(
        authority, committedAt, validated, bindingId, messageId, messageSequence,
        messageSha, eventId, eventSequence, eventKey, eventSha);
    String bindingSha = ContractJson.sha256Hex(binding);
    return new Expected(
        authority,
        committedAt,
        validated,
        bindingId,
        messageId,
        messageSequence,
        messageSha,
        eventId,
        eventSequence,
        eventKey,
        eventSha,
        bindingSha,
        traceId,
        audience,
        empty,
        sourceRefs,
        eventPayload);
  }

  private ObjectNode bindingDocument(
      Authority authority,
      Instant committedAt,
      ValidatedDraft draft,
      String bindingId,
      String messageId,
      long messageSequence,
      String messageSha,
      String eventId,
      long eventSequence,
      String eventKey,
      String eventSha) {
    ObjectNode value = mapper.createObjectNode();
    value.put("schema_version", BINDING_SCHEMA);
    value.put("id", bindingId);
    value.put("tenant_surrogate", authority.tenantSurrogate());
    value.put("case_id", authority.caseId());
    value.put("room_id", authority.roomId());
    value.put("room_type", authority.roomType());
    value.put("flow_instance_id", authority.flowInstanceId());
    value.put("epoch_id", authority.epochId());
    value.put("hearing_epoch", authority.hearingEpoch());
    value.put("writer_mode", authority.writerMode());
    value.put("fencing_token", authority.fencingToken());
    value.put("receipt_id", authority.receiptId());
    value.put("receipt_hash", authority.receiptHash());
    value.put("source_stage", authority.sourceStage());
    value.put("source_stage_sequence", authority.sourceStageSequence());
    value.put("source_process_revision", authority.sourceProcessRevision());
    value.put("source_room_revision", authority.sourceRoomRevision());
    value.put("result_stage", authority.resultStage());
    value.put("result_stage_sequence", authority.resultStageSequence());
    value.put("process_revision", authority.processRevision());
    value.put("room_revision", authority.roomRevision());
    value.put("ordinal", draft.ordinal());
    value.put("message_stage", draft.draft().stage().name());
    value.put("message_stage_sequence", draft.draft().stageSequence());
    value.put("publication_key", draft.publicationKey());
    value.put("message_id", messageId);
    value.put("message_sequence_no", messageSequence);
    value.put("message_sha256", messageSha);
    value.put("event_id", eventId);
    value.put("event_sequence_no", eventSequence);
    value.put("event_key", eventKey);
    value.put("event_sha256", eventSha);
    value.put("committed_at", committedAt.toString());
    value.put("created_at", committedAt.toString());
    value.put("created_by", CREATED_BY);
    return value;
  }

  private void insert(Expected expected) {
    HearingPublicTranscriptPolicy.Draft draft = expected.validated().draft();
    int message =
        jdbc.update(
            """
            insert into room_message (
              id, case_id, room_id, sequence_no, sender_type, sender_role, sender_id,
              audience_json, audience_actor_ids_json, message_source, message_type,
              message_text, attachment_refs_json, agent_run_id, hearing_round,
              idempotency_key, created_at, trace_id, created_by)
            values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?,
                    cast(? as jsonb), ?, null, ?, ?, ?, ?)
            """,
            expected.messageId(),
            expected.authority().caseId(),
            expected.authority().roomId(),
            expected.messageSequence(),
            draft.senderType().name(),
            draft.senderRole(),
            draft.senderId(),
            canonical(expected.audience()),
            canonical(expected.empty()),
            draft.messageSource().name(),
            draft.messageType().name(),
            draft.text(),
            canonical(expected.empty()),
            draft.agentRunId(),
            expected.validated().publicationKey(),
            Timestamp.from(expected.committedAt()),
            expected.traceId(),
            draft.senderId());
    require(message == 1, "Target Hearing public transcript message insert failed");

    int event =
        jdbc.update(
            """
            insert into case_timeline_event (
              id, case_id, dossier_id, event_type, event_time, source_refs_json,
              event_json, sequence_no, room_id, audience_json,
              audience_actor_ids_json, event_key, created_at, created_by)
            values (?, ?, null, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?,
                    cast(? as jsonb), cast(? as jsonb), ?, ?, ?)
            """,
            expected.eventId(),
            expected.authority().caseId(),
            EVENT_TYPE,
            Timestamp.from(expected.committedAt()),
            canonical(expected.sourceRefs()),
            canonical(expected.eventPayload()),
            expected.eventSequence(),
            expected.authority().roomId(),
            canonical(expected.audience()),
            canonical(expected.empty()),
            expected.eventKey(),
            Timestamp.from(expected.committedAt()),
            CREATED_BY);
    require(event == 1, "Target Hearing public transcript timeline insert failed");

    Authority authority = expected.authority();
    int binding =
        jdbc.update(
            """
            insert into hearing_public_transcript_binding (
              schema_version, id, tenant_surrogate, case_id, room_id, room_type,
              flow_instance_id, epoch_id, hearing_epoch, writer_mode, fencing_token,
              receipt_id, receipt_hash, source_stage, source_stage_sequence,
              source_process_revision, source_room_revision, result_stage,
              result_stage_sequence, process_revision, room_revision, ordinal,
              message_stage, message_stage_sequence, publication_key, message_id,
              message_sequence_no, message_sha256, event_id, event_sequence_no,
              event_key, event_sha256, committed_at, binding_sha256, created_at, created_by)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            BINDING_SCHEMA,
            expected.bindingId(),
            authority.tenantSurrogate(),
            authority.caseId(),
            authority.roomId(),
            authority.roomType(),
            authority.flowInstanceId(),
            authority.epochId(),
            authority.hearingEpoch(),
            authority.writerMode(),
            authority.fencingToken(),
            authority.receiptId(),
            authority.receiptHash(),
            authority.sourceStage(),
            authority.sourceStageSequence(),
            authority.sourceProcessRevision(),
            authority.sourceRoomRevision(),
            authority.resultStage(),
            authority.resultStageSequence(),
            authority.processRevision(),
            authority.roomRevision(),
            expected.validated().ordinal(),
            draft.stage().name(),
            draft.stageSequence(),
            expected.validated().publicationKey(),
            expected.messageId(),
            expected.messageSequence(),
            expected.messageSha(),
            expected.eventId(),
            expected.eventSequence(),
            expected.eventKey(),
            expected.eventSha(),
            Timestamp.from(expected.committedAt()),
            expected.bindingSha(),
            Timestamp.from(expected.committedAt()),
            CREATED_BY);
    require(binding == 1, "Target Hearing public transcript binding insert failed");
  }

  private void verifyReplay(Expected expected) {
    List<Stored> rows =
        jdbc.query(
            """
            select binding.schema_version, binding.id, binding.tenant_surrogate,
                   binding.case_id, binding.room_id, binding.room_type,
                   binding.flow_instance_id, binding.epoch_id, binding.hearing_epoch,
                   binding.writer_mode, binding.fencing_token, binding.receipt_id,
                   binding.receipt_hash, binding.source_stage,
                   binding.source_stage_sequence, binding.source_process_revision,
                   binding.source_room_revision, binding.result_stage,
                   binding.result_stage_sequence, binding.process_revision,
                   binding.room_revision, binding.ordinal, binding.message_stage,
                   binding.message_stage_sequence, binding.publication_key,
                   binding.message_id, binding.message_sequence_no,
                   binding.message_sha256, binding.event_id,
                   binding.event_sequence_no, binding.event_key,
                   binding.event_sha256, binding.committed_at,
                   binding.binding_sha256, binding.created_at, binding.created_by,
                   message.sender_type, message.sender_role, message.sender_id,
                   message.audience_json::text, message.audience_actor_ids_json::text,
                   message.message_source, message.message_type, message.message_text,
                   message.attachment_refs_json::text, message.agent_run_id,
                   message.hearing_round, message.created_at as message_created_at,
                   message.trace_id, message.created_by as message_created_by,
                   timeline.event_type, timeline.event_time,
                   timeline.source_refs_json::text, timeline.event_json::text,
                   timeline.audience_json::text,
                   timeline.audience_actor_ids_json::text,
                   timeline.created_at as event_created_at,
                   timeline.created_by as event_created_by
              from hearing_public_transcript_binding binding
              join room_message message
                on message.id = binding.message_id
               and message.case_id = binding.case_id
               and message.room_id = binding.room_id
               and message.sequence_no = binding.message_sequence_no
               and message.idempotency_key = binding.publication_key
              join case_timeline_event timeline
                on timeline.id = binding.event_id
               and timeline.case_id = binding.case_id
               and timeline.room_id = binding.room_id
               and timeline.sequence_no = binding.event_sequence_no
               and timeline.event_key = binding.event_key
             where binding.receipt_id = ? and binding.ordinal = ?
             for update of binding, message, timeline
            """,
            JdbcTargetHearingPublicTranscriptCommitter::stored,
            expected.authority().receiptId(),
            expected.validated().ordinal());
    Stored row = one(rows, "Target Hearing public transcript STRICT_REPLAY row is absent or ambiguous");
    Authority authority = expected.authority();
    HearingPublicTranscriptPolicy.Draft draft = expected.validated().draft();
    boolean bindingExact =
        row.schemaVersion().equals(BINDING_SCHEMA)
            && row.bindingId().equals(expected.bindingId())
            && row.tenantSurrogate().equals(authority.tenantSurrogate())
            && row.caseId().equals(authority.caseId())
            && row.roomId().equals(authority.roomId())
            && row.roomType().equals(authority.roomType())
            && row.flowInstanceId().equals(authority.flowInstanceId())
            && row.epochId().equals(authority.epochId())
            && row.hearingEpoch() == authority.hearingEpoch()
            && row.writerMode().equals(authority.writerMode())
            && row.fencingToken() == authority.fencingToken()
            && row.receiptId().equals(authority.receiptId())
            && row.receiptHash().equals(authority.receiptHash())
            && row.sourceStage().equals(authority.sourceStage())
            && row.sourceStageSequence() == authority.sourceStageSequence()
            && row.sourceProcessRevision() == authority.sourceProcessRevision()
            && row.sourceRoomRevision() == authority.sourceRoomRevision()
            && row.resultStage().equals(authority.resultStage())
            && row.resultStageSequence() == authority.resultStageSequence()
            && row.processRevision() == authority.processRevision()
            && row.roomRevision() == authority.roomRevision()
            && row.ordinal() == expected.validated().ordinal()
            && row.messageStage().equals(draft.stage().name())
            && row.messageStageSequence() == draft.stageSequence()
            && row.publicationKey().equals(expected.validated().publicationKey())
            && row.messageId().equals(expected.messageId())
            && row.messageSequence() == expected.messageSequence()
            && row.messageSha().equals(expected.messageSha())
            && row.eventId().equals(expected.eventId())
            && row.eventSequence() == expected.eventSequence()
            && row.eventKey().equals(expected.eventKey())
            && row.eventSha().equals(expected.eventSha())
            && row.committedAt().equals(expected.committedAt())
            && row.bindingSha().equals(expected.bindingSha())
            && row.createdAt().equals(expected.committedAt())
            && row.createdBy().equals(CREATED_BY);
    boolean messageExact =
        row.senderType().equals(draft.senderType().name())
            && row.senderRole().equals(draft.senderRole())
            && row.senderId().equals(draft.senderId())
            && json(row.audience()).equals(expected.audience())
            && json(row.audienceActorIds()).equals(expected.empty())
            && row.messageSource().equals(draft.messageSource().name())
            && row.messageType().equals(draft.messageType().name())
            && row.messageText().equals(draft.text())
            && json(row.attachments()).equals(expected.empty())
            && Objects.equals(row.agentRunId(), draft.agentRunId())
            && row.hearingRound() == null
            && row.messageCreatedAt().equals(expected.committedAt())
            && row.traceId().equals(expected.traceId())
            && row.messageCreatedBy().equals(draft.senderId());
    List<String> eventDrift = new ArrayList<>();
    if (!row.eventType().equals(EVENT_TYPE)) eventDrift.add("type");
    if (!row.eventTime().equals(expected.committedAt())) eventDrift.add("time");
    if (!json(row.sourceRefs()).equals(expected.sourceRefs())) eventDrift.add("source-refs");
    JsonNode persistedEventPayload = json(row.eventPayload());
    List<String> payloadDrift = new ArrayList<>();
    expected.eventPayload().fieldNames().forEachRemaining(
        field -> {
          if (!jsonValueEquals(
              expected.eventPayload().path(field), persistedEventPayload.path(field))) {
            payloadDrift.add(field);
          }
        });
    if (persistedEventPayload.size() != expected.eventPayload().size()) {
      payloadDrift.add("field-set");
    }
    if (!payloadDrift.isEmpty()) eventDrift.add("payload" + payloadDrift);
    if (!json(row.eventAudience()).equals(expected.audience())) eventDrift.add("audience");
    if (!json(row.eventAudienceActorIds()).equals(expected.empty())) {
      eventDrift.add("audience-actors");
    }
    if (!row.eventCreatedAt().equals(expected.committedAt())) eventDrift.add("created-at");
    if (!row.eventCreatedBy().equals(CREATED_BY)) eventDrift.add("created-by");
    List<String> drift = new ArrayList<>();
    if (!bindingExact) drift.add("binding");
    if (!messageExact) drift.add("message");
    if (!eventDrift.isEmpty()) drift.add("event" + eventDrift);
    require(
        drift.isEmpty(),
        "Target Hearing public transcript STRICT_REPLAY bytes drifted: " + drift);
  }

  private long nextSequence(String table, String column, String value) {
    Long next =
        jdbc.queryForObject(
            "select coalesce(max(sequence_no), 0) + 1 from " + table + " where " + column + " = ?",
            Long.class,
            value);
    require(next != null && next > 0, "Target Hearing public transcript sequence is invalid");
    return next;
  }

  private int bindingCount(String receiptId) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from hearing_public_transcript_binding where receipt_id = ?",
            Integer.class,
            receiptId);
    return count == null ? 0 : count;
  }

  private List<StoredSequences> storedSequences(String receiptId) {
    return jdbc.query(
        """
        select ordinal, message_sequence_no, event_sequence_no
          from hearing_public_transcript_binding
         where receipt_id = ?
         order by ordinal
         for update
        """,
        (row, ignored) -> new StoredSequences(row.getInt(1), row.getLong(2), row.getLong(3)),
        receiptId);
  }

  private void registerAfterCommit(String caseId) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            afterCommitNotifier.accept(caseId);
          }
        });
  }

  private String stable(String value, int length) {
    ObjectNode seed = mapper.createObjectNode();
    seed.put("seed", value);
    return ContractJson.sha256Hex(seed).substring(0, length);
  }

  private String canonical(JsonNode value) {
    return ContractJson.canonicalString(value);
  }

  private static boolean jsonValueEquals(JsonNode first, JsonNode second) {
    if (first.isNumber() && second.isNumber()) {
      return first.decimalValue().compareTo(second.decimalValue()) == 0;
    }
    return first.equals(second);
  }

  private JsonNode json(String value) {
    try {
      return mapper.readTree(value);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Target Hearing public transcript persisted JSON is invalid", failure);
    }
  }

  private static Authority authority(ResultSet row, int ignored) throws SQLException {
    return new Authority(
        row.getString("receipt_id"),
        row.getString("receipt_hash"),
        row.getString("operation_type"),
        row.getString("operation_key"),
        row.getString("request_hash"),
        row.getString("tenant_surrogate"),
        row.getString("case_id"),
        row.getString("flow_instance_id"),
        row.getString("epoch_id"),
        row.getString("room_id"),
        row.getString("room_type"),
        row.getLong("hearing_epoch"),
        row.getString("writer_mode"),
        row.getLong("fencing_token"),
        row.getString("source_stage"),
        row.getInt("source_stage_sequence"),
        row.getLong("source_process_revision"),
        row.getLong("source_room_revision"),
        row.getString("stage_code"),
        row.getInt("stage_sequence"),
        row.getLong("process_revision"),
        row.getLong("room_revision"),
        row.getString("result_ref"),
        row.getString("result_hash"),
        row.getLong("committed_event_sequence"),
        nullableLong(row, "temporal_history_event_id"),
        instant(row, "committed_at"));
  }

  private static Stored stored(ResultSet row, int ignored) throws SQLException {
    return new Stored(
        row.getString("schema_version"), row.getString("id"),
        row.getString("tenant_surrogate"), row.getString("case_id"),
        row.getString("room_id"), row.getString("room_type"),
        row.getString("flow_instance_id"), row.getString("epoch_id"),
        row.getLong("hearing_epoch"), row.getString("writer_mode"),
        row.getLong("fencing_token"), row.getString("receipt_id"),
        row.getString("receipt_hash"), row.getString("source_stage"),
        row.getInt("source_stage_sequence"), row.getLong("source_process_revision"),
        row.getLong("source_room_revision"), row.getString("result_stage"),
        row.getInt("result_stage_sequence"), row.getLong("process_revision"),
        row.getLong("room_revision"), row.getInt("ordinal"),
        row.getString("message_stage"), row.getInt("message_stage_sequence"),
        row.getString("publication_key"), row.getString("message_id"),
        row.getLong("message_sequence_no"), row.getString("message_sha256"),
        row.getString("event_id"), row.getLong("event_sequence_no"),
        row.getString("event_key"), row.getString("event_sha256"),
        instant(row, "committed_at"), row.getString("binding_sha256"),
        instant(row, "created_at"), row.getString("created_by"),
        row.getString("sender_type"), row.getString("sender_role"),
        row.getString("sender_id"), row.getString("audience_json"),
        row.getString("audience_actor_ids_json"), row.getString("message_source"),
        row.getString("message_type"), row.getString("message_text"),
        row.getString("attachment_refs_json"), row.getString("agent_run_id"),
        nullableInteger(row, "hearing_round"), instant(row, "message_created_at"),
        row.getString("trace_id"), row.getString("message_created_by"),
        row.getString("event_type"), instant(row, "event_time"),
        row.getString("source_refs_json"), row.getString("event_json"),
        row.getString("audience_json"), row.getString("audience_actor_ids_json"),
        instant(row, "event_created_at"), row.getString("event_created_by"));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static Long nullableLong(ResultSet row, String column) throws SQLException {
    long value = row.getLong(column);
    return row.wasNull() ? null : value;
  }

  private static Integer nullableInteger(ResultSet row, String column) throws SQLException {
    int value = row.getInt(column);
    return row.wasNull() ? null : value;
  }

  private static <T> T one(List<T> rows, String message) {
    require(rows.size() == 1, message);
    return rows.getFirst();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  public enum CommitMode {
    NEW_COMMIT,
    STRICT_REPLAY
  }

  public record CommitResult(String receiptId, String receiptHash, List<Publication> publications) {
    public CommitResult {
      Objects.requireNonNull(receiptId, "receiptId");
      require(HASH.matcher(receiptHash).matches(), "receiptHash is invalid");
      publications = List.copyOf(publications);
    }
  }

  public record Publication(
      int ordinal,
      String publicationKey,
      String messageId,
      long messageSequence,
      String messageSha256,
      String eventId,
      long eventSequence,
      String eventSha256,
      String bindingSha256) {}

  private record Presentation(String role, String senderId, MessageType messageType) {}

  private record ValidatedDraft(
      int ordinal, String publicationKey, HearingPublicTranscriptPolicy.Draft draft) {}

  private record StoredSequences(int ordinal, long messageSequence, long eventSequence) {}

  private record Authority(
      String receiptId,
      String receiptHash,
      String operationType,
      String operationKey,
      String requestHash,
      String tenantSurrogate,
      String caseId,
      String flowInstanceId,
      String epochId,
      String roomId,
      String roomType,
      long hearingEpoch,
      String writerMode,
      long fencingToken,
      String sourceStage,
      int sourceStageSequence,
      long sourceProcessRevision,
      long sourceRoomRevision,
      String resultStage,
      int resultStageSequence,
      long processRevision,
      long roomRevision,
      String resultRef,
      String resultHash,
      long committedEventSequence,
      Long temporalHistoryEventId,
      Instant committedAt) {}

  private record Expected(
      Authority authority,
      Instant committedAt,
      ValidatedDraft validated,
      String bindingId,
      String messageId,
      long messageSequence,
      String messageSha,
      String eventId,
      long eventSequence,
      String eventKey,
      String eventSha,
      String bindingSha,
      String traceId,
      ArrayNode audience,
      ArrayNode empty,
      ArrayNode sourceRefs,
      ObjectNode eventPayload) {
    Publication publication() {
      return new Publication(
          validated.ordinal(), validated.publicationKey(), messageId, messageSequence,
          messageSha, eventId, eventSequence, eventSha, bindingSha);
    }
  }

  private record Stored(
      String schemaVersion, String bindingId, String tenantSurrogate, String caseId,
      String roomId, String roomType, String flowInstanceId, String epochId,
      long hearingEpoch, String writerMode, long fencingToken, String receiptId,
      String receiptHash, String sourceStage, int sourceStageSequence,
      long sourceProcessRevision, long sourceRoomRevision, String resultStage,
      int resultStageSequence, long processRevision, long roomRevision, int ordinal,
      String messageStage, int messageStageSequence, String publicationKey,
      String messageId, long messageSequence, String messageSha, String eventId,
      long eventSequence, String eventKey, String eventSha, Instant committedAt,
      String bindingSha, Instant createdAt, String createdBy, String senderType,
      String senderRole, String senderId, String audience, String audienceActorIds,
      String messageSource, String messageType, String messageText, String attachments,
      String agentRunId, Integer hearingRound, Instant messageCreatedAt, String traceId,
      String messageCreatedBy, String eventType, Instant eventTime, String sourceRefs,
      String eventPayload, String eventAudience, String eventAudienceActorIds,
      Instant eventCreatedAt, String eventCreatedBy) {}
}

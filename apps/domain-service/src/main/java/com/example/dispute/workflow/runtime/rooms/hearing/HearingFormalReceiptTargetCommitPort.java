package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.hearing.application.HearingPublicTranscriptPolicy;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bridges the existing Hearing formal receipt service into the shared target finalization tx. */
public final class HearingFormalReceiptTargetCommitPort implements TargetHearingFormalCommitPort {
  private final TargetHearingFormalCompletion completion;
  private final JdbcTemplate jdbc;
  private final JdbcTargetHearingPublicTranscriptCommitter transcript;
  private final JdbcTargetHearingRoomStartLoader startLoader;
  private final ObjectMapper mapper;
  private final HearingPublicTranscriptPolicy transcriptPolicy;

  public HearingFormalReceiptTargetCommitPort(TargetHearingFormalCompletion completion) {
    this.completion = Objects.requireNonNull(completion, "completion");
    this.jdbc = null;
    this.transcript = null;
    this.startLoader = null;
    this.mapper = null;
    this.transcriptPolicy = null;
  }

  public HearingFormalReceiptTargetCommitPort(
      DataSource dataSource,
      TargetHearingFormalCompletion completion,
      JdbcTargetHearingPublicTranscriptCommitter transcript,
      ObjectMapper mapper) {
    DataSource exactDataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.completion = Objects.requireNonNull(completion, "completion");
    this.jdbc = new JdbcTemplate(exactDataSource);
    this.transcript = Objects.requireNonNull(transcript, "transcript");
    this.startLoader = new JdbcTargetHearingRoomStartLoader(exactDataSource);
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    this.transcriptPolicy = new HearingPublicTranscriptPolicy();
  }

  @Override
  public CommitResult commit(Connection transaction, TargetHearingFinalizationRequest request) {
    if (transaction == null) {
      throw new IllegalArgumentException("target Hearing formal commit requires the outer transaction");
    }
    request = Objects.requireNonNull(request, "request");
    boolean replay = transcript != null && receiptExists(request);
    HearingStageReceipt receipt = completion.commit(request.formalCommand());
    if (transcript != null) {
      var formalCommand = request.formalCommand();
      var authorityCommit = formalCommand.authorityCommit();
      transcript.commit(
          replay
              ? JdbcTargetHearingPublicTranscriptCommitter.CommitMode.STRICT_REPLAY
              : JdbcTargetHearingPublicTranscriptCommitter.CommitMode.NEW_COMMIT,
          receipt,
          startLoader.load(request),
          authorityCommit.committedAt(),
          transcriptPolicy.agentFinalized(
              authorityCommit.authority().stage(),
              formalPayload(formalCommand),
              formalCommand.agentRunId()));
      bindV4PublicFrames(request, receipt, formalPayload(formalCommand));
    }
    return new CommitResult(request.formalObjectId(), receipt.committed().receiptHash());
  }

  private void bindV4PublicFrames(
      TargetHearingFinalizationRequest request,
      HearingStageReceipt receipt,
      ObjectNode formalOutput) {
    String schema = formalOutput.path("schema_version").asText();
    if (!"hearing_intake_questions.v5".equals(schema)
        && !"hearing_intake_synthesis.v5".equals(schema)) {
      return;
    }
    JsonNode frames = formalOutput.path("public_frames");
    if (!frames.isArray() || frames.size() < 2 || frames.size() > 11) {
      throw new IllegalStateException("target Hearing V4 public frames are invalid");
    }
    var graph = request.command().request().command();
    List<PublicFrameRow> storedFrames = jdbc.query(
        """
        select id, frame_id, frame_sequence, frame_type, public_header::text,
               public_text, public_text_sha256
          from agent_run_public_frame
         where agent_run_id = ? and agent_run_attempt_id = ?
         order by frame_sequence
         for key share
        """,
        (row, ignored) -> new PublicFrameRow(
            row.getString(1), row.getString(2), row.getInt(3), row.getString(4),
            row.getString(5), row.getString(6), row.getString(7)),
        graph.logicalRunId(), graph.attemptId());
    List<TranscriptFrameRow> transcriptFrames = jdbc.query(
        """
        select ordinal, message_id
          from hearing_public_transcript_binding
         where receipt_id = ? and ordinal < ?
         order by ordinal
         for key share
        """,
        (row, ignored) -> new TranscriptFrameRow(row.getInt(1), row.getString(2)),
        receipt.committed().receiptId(), frames.size());
    if (storedFrames.size() != frames.size() || transcriptFrames.size() != frames.size()) {
      throw new IllegalStateException("target Hearing V4 frame authority is incomplete");
    }
    for (int index = 0; index < frames.size(); index++) {
      JsonNode frame = frames.get(index);
      PublicFrameRow stored = storedFrames.get(index);
      TranscriptFrameRow transcriptFrame = transcriptFrames.get(index);
      JsonNode header;
      try {
        header = mapper.readTree(stored.publicHeaderJson());
      } catch (Exception failure) {
        throw new IllegalStateException("target Hearing V4 frame header is invalid", failure);
      }
      int sequence = index + 1;
      if (stored.frameSequence() != sequence
          || transcriptFrame.ordinal() != index
          || frame.path("frame_sequence").asInt(0) != sequence
          || !frame.path("frame_type").asText().equals(stored.frameType())
          || header.path("frame_sequence").asInt(0) != sequence
          || !header.path("frame_type").asText().equals(stored.frameType())
          || !frame.path("public_text").asText().equals(stored.publicText())
          || !frame.path("public_text_hash").asText().equals(stored.publicTextSha256())) {
        throw new IllegalStateException("target Hearing V4 frame authority differs from terminal output");
      }
      String bindingId = "HEARING_V4_FRAME_" + ContractJson.sha256Hex(mapper.valueToTree(List.of(
          receipt.committed().receiptId(), stored.frameId(), transcriptFrame.messageId())))
          .substring(0, 32).toUpperCase();
      int inserted = jdbc.update(
          """
          insert into hearing_public_frame_binding_v4 (
              id, case_id, flow_instance_id, receipt_id,
              agent_run_id, agent_run_attempt_id, public_frame_row_id,
              frame_id, frame_sequence, frame_type, authority_ref,
              public_text_sha256, message_id, created_at, created_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'hearing-flow-v2')
          on conflict (receipt_id, frame_sequence) do nothing
          """,
          bindingId,
          graph.caseId(),
          request.formalCommand().authorityCommit().authority().flowInstanceId(),
          receipt.committed().receiptId(),
          graph.logicalRunId(),
          graph.attemptId(),
          stored.id(),
          stored.frameId(),
          sequence,
          stored.frameType(),
          frame.path("authority_ref").asText(),
          stored.publicTextSha256(),
          transcriptFrame.messageId(),
          java.time.OffsetDateTime.ofInstant(
              request.formalCommand().authorityCommit().committedAt(),
              java.time.ZoneOffset.UTC));
      if (inserted != 0 && inserted != 1) {
        throw new IllegalStateException("target Hearing V4 frame binding insert failed");
      }
      Integer exact = jdbc.queryForObject(
          """
          select count(*)
            from hearing_public_frame_binding_v4
           where id = ? and case_id = ? and flow_instance_id = ? and receipt_id = ?
             and agent_run_id = ? and agent_run_attempt_id = ?
             and public_frame_row_id = ? and frame_id = ? and frame_sequence = ?
             and frame_type = ? and authority_ref = ? and public_text_sha256 = ?
             and message_id = ?
          """,
          Integer.class,
          bindingId,
          graph.caseId(),
          request.formalCommand().authorityCommit().authority().flowInstanceId(),
          receipt.committed().receiptId(),
          graph.logicalRunId(),
          graph.attemptId(),
          stored.id(),
          stored.frameId(),
          sequence,
          stored.frameType(),
          frame.path("authority_ref").asText(),
          stored.publicTextSha256(),
          transcriptFrame.messageId());
      if (!Integer.valueOf(1).equals(exact)) {
        throw new IllegalStateException("target Hearing V4 frame binding replay differs");
      }
    }
  }

  private boolean receiptExists(TargetHearingFinalizationRequest request) {
    var commit = request.formalCommand().authorityCommit();
    List<String> rows = jdbc.query(
        """
        select receipt_id
          from hearing_domain_receipt
         where tenant_surrogate = ? and operation_key = ?
         for update
        """,
        (row, ignored) -> row.getString(1),
        commit.authority().tenantSurrogate(),
        commit.operationKey());
    if (rows.size() > 1) {
      throw new IllegalStateException("Target Hearing formal receipt is ambiguous");
    }
    return !rows.isEmpty();
  }

  private ObjectNode formalPayload(
      TargetHearingFinalizationRequest.FormalCommand command) {
    String json = switch (command) {
      case TargetHearingFinalizationRequest.GeneratedAction action ->
          action.value().transition().sourceOutputJson();
      case TargetHearingFinalizationRequest.MatrixSynthesis matrix ->
          matrix.value().payloadJson();
      case TargetHearingFinalizationRequest.Decision decision ->
          decision.value().payloadJson();
    };
    try {
      JsonNode value = mapper.readTree(json);
      if (!(value instanceof ObjectNode object)) {
        throw new IllegalStateException("Target Hearing formal public payload is not an object");
      }
      return object;
    } catch (Exception failure) {
      throw new IllegalStateException("Target Hearing formal public payload is invalid JSON", failure);
    }
  }

  private record PublicFrameRow(
      String id,
      String frameId,
      int frameSequence,
      String frameType,
      String publicHeaderJson,
      String publicText,
      String publicTextSha256) {}

  private record TranscriptFrameRow(int ordinal, String messageId) {}
}

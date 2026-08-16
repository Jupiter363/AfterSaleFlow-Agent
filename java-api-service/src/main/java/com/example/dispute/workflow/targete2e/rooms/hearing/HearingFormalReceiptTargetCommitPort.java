package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.hearing.application.HearingPublicTranscriptPolicy;
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
    }
    return new CommitResult(request.formalObjectId(), receipt.committed().receiptHash());
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
          action.value().payloadJson();
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
}

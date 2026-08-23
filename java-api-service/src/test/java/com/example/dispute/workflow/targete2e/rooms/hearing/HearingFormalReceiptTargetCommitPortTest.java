package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingFormalFinalizer;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.temporal.room.hearing.HearingCommittedReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

class HearingFormalReceiptTargetCommitPortTest {

  private static final String RUN_ID = "target-hearing-run:frame-binding";
  private static final String ATTEMPT_ID = RUN_ID + ":1";
  private static final String RECEIPT_ID = "HEARING_RECEIPT_FRAME_BINDING";

  @Test
  void bindsEveryCommittedV4FrameToItsFormalMessageAndReplaysWithoutNewRows() {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode formal = formalFrames(mapper);
    TargetHearingFinalizationRequest request = request();
    HearingStageReceipt receipt = receipt();

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AtomicReference<String> transcriptSql = new AtomicReference<>();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(invocation -> {
          String sql = invocation.getArgument(0);
          @SuppressWarnings("unchecked")
          RowMapper<Object> rowMapper = invocation.getArgument(1);
          List<Object> rows = new ArrayList<>();
          if (sql.contains("from agent_run_public_frame")) {
            rows.add(rowMapper.mapRow(frameRow(
                1, "FRAME_ROW_1", "FRAME_1", "HEARING_INTAKE_SYNTHESIS_LEAD",
                "Lead frame"), 0));
            rows.add(rowMapper.mapRow(frameRow(
                2, "FRAME_ROW_2", "FRAME_2", "REBIND_ISSUE_SYNTHESIS",
                "Issue frame"), 1));
            return rows;
          }
          if (sql.contains("from hearing_public_transcript_binding")) {
            transcriptSql.set(sql);
            rows.add(rowMapper.mapRow(transcriptRow(0, "MESSAGE_FRAME_1"), 0));
            rows.add(rowMapper.mapRow(transcriptRow(1, "MESSAGE_FRAME_2"), 1));
            return rows;
          }
          throw new AssertionError("unexpected frame-binding query: " + sql);
        });
    AtomicInteger insertCalls = new AtomicInteger();
    when(jdbc.update(contains("insert into hearing_public_frame_binding_v4"), any(Object[].class)))
        .thenAnswer(invocation -> insertCalls.getAndIncrement() < 2 ? 1 : 0);
    when(jdbc.queryForObject(
        contains("from hearing_public_frame_binding_v4"), eq(Integer.class),
        any(Object[].class)))
        .thenReturn(1);

    HearingFormalReceiptTargetCommitPort port = new HearingFormalReceiptTargetCommitPort(
        mock(DataSource.class), mock(TargetHearingFormalCompletion.class),
        mock(JdbcTargetHearingPublicTranscriptCommitter.class), mapper);
    ReflectionTestUtils.setField(port, "jdbc", jdbc);

    ReflectionTestUtils.invokeMethod(port, "bindV4PublicFrames", request, receipt, formal);
    ReflectionTestUtils.invokeMethod(port, "bindV4PublicFrames", request, receipt, formal.deepCopy());

    assertThat(insertCalls).hasValue(4);
    assertThat(transcriptSql.get()).contains("ordinal < ?", "order by ordinal");
    verify(jdbc, times(4)).queryForObject(
        contains("from hearing_public_frame_binding_v4"), eq(Integer.class),
        any(Object[].class));

    ObjectNode drifted = formal.deepCopy();
    ((ObjectNode) drifted.withArray("public_frames").get(0)).put("public_text", "drifted");
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
        port, "bindV4PublicFrames", request, receipt, drifted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs from terminal output");
    assertThat(insertCalls).hasValue(4);
  }

  private static TargetHearingFinalizationRequest request() {
    TargetHearingFinalizationRequest request = mock(TargetHearingFinalizationRequest.class);
    CommitCommand command = mock(CommitCommand.class);
    ExecuteAgentRunRequest execution = mock(ExecuteAgentRunRequest.class);
    RoomGraphCommand graph = mock(RoomGraphCommand.class);
    when(request.command()).thenReturn(command);
    when(command.request()).thenReturn(execution);
    when(execution.command()).thenReturn(graph);
    when(graph.logicalRunId()).thenReturn(RUN_ID);
    when(graph.attemptId()).thenReturn(ATTEMPT_ID);
    when(graph.caseId()).thenReturn("case-1");

    HearingFormalFinalizer.MatrixSynthesisCommand matrix =
        mock(HearingFormalFinalizer.MatrixSynthesisCommand.class);
    HearingAuthorityCommit commit = mock(HearingAuthorityCommit.class);
    HearingAuthorityExpectation authority = mock(HearingAuthorityExpectation.class);
    when(matrix.authorityCommit()).thenReturn(commit);
    when(commit.authority()).thenReturn(authority);
    when(commit.committedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
    when(authority.flowInstanceId()).thenReturn("flow-1");
    when(request.formalCommand())
        .thenReturn(new TargetHearingFinalizationRequest.MatrixSynthesis(matrix));
    return request;
  }

  private static HearingStageReceipt receipt() {
    HearingStageReceipt receipt = mock(HearingStageReceipt.class);
    HearingCommittedReceipt committed = mock(HearingCommittedReceipt.class);
    when(receipt.committed()).thenReturn(committed);
    when(committed.receiptId()).thenReturn(RECEIPT_ID);
    return receipt;
  }

  private static ResultSet frameRow(
      int sequence, String rowId, String frameId, String type, String text) throws Exception {
    ResultSet row = mock(ResultSet.class);
    when(row.getString(1)).thenReturn(rowId);
    when(row.getString(2)).thenReturn(frameId);
    when(row.getInt(3)).thenReturn(sequence);
    when(row.getString(4)).thenReturn(type);
    when(row.getString(5)).thenReturn(
        "{\"schema_version\":\"hearing-public-frame.v5\",\"frame_sequence\":"
            + sequence + ",\"frame_type\":\"" + type + "\"}");
    when(row.getString(6)).thenReturn(text);
    when(row.getString(7)).thenReturn(textHash(text));
    return row;
  }

  private static ResultSet transcriptRow(int ordinal, String messageId) throws Exception {
    ResultSet row = mock(ResultSet.class);
    when(row.getInt(1)).thenReturn(ordinal);
    when(row.getString(2)).thenReturn(messageId);
    return row;
  }

  private static ObjectNode formalFrames(ObjectMapper mapper) {
    ObjectNode formal = mapper.createObjectNode();
    formal.put("schema_version", "hearing_intake_synthesis.v5");
    var frames = formal.putArray("public_frames");
    frames.addObject()
        .put("frame_sequence", 1)
        .put("frame_type", "HEARING_INTAKE_SYNTHESIS_LEAD")
        .put("authority_ref", "TRANSITION_SET_1")
        .put("public_text", "Lead frame")
        .put("public_text_hash", textHash("Lead frame"));
    frames.addObject()
        .put("frame_sequence", 2)
        .put("frame_type", "REBIND_ISSUE_SYNTHESIS")
        .put("authority_ref", "ISSUE_1")
        .put("public_text", "Issue frame")
        .put("public_text_hash", textHash("Issue frame"));
    return formal;
  }

  private static String textHash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}

package com.example.dispute.workflow.runtime.rooms.outcome;

import com.example.dispute.evaluation.application.EvaluationAgentClient;
import com.example.dispute.evaluation.application.EvaluationAgentResult;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/** Target-profile deterministic Java evaluation: it consumes a CLOSED snapshot and never calls HTTP. */
public final class TargetDeterministicEvaluationAgentClient implements EvaluationAgentClient {
  private final ObjectMapper mapper;

  public TargetDeterministicEvaluationAgentClient(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
  }

  @Override
  public EvaluationAgentResult analyze(JsonNode closedCaseSnapshot, String traceId, String requestId) {
    if (closedCaseSnapshot == null || traceId == null || traceId.isBlank()
        || requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("target evaluation requires an immutable closed snapshot and request identity");
    }
    String snapshotHash = ContractJson.sha256Hex(closedCaseSnapshot);
    String caseId = closedCaseSnapshot.path("case_id").asText();
    if (caseId.isBlank()) {
      throw new IllegalArgumentException("target evaluation snapshot has no case identity");
    }
    var report = mapper.createObjectNode();
    report.put("schema_version", "production-runtime-deterministic-evaluation.v1");
    report.put("case_id", caseId);
    report.put("evaluation_status", "COMPLETED");
    report.put("trace_id", traceId);
    report.put("request_id", requestId);
    report.put("closed_snapshot_hash", snapshotHash);
    report.put("evaluation_mode", "JAVA_DETERMINISTIC_NO_EXTERNAL_EFFECT");
    report.put("evaluator_model", "production-runtime-java-deterministic");
    report.put("prompt_version", "production-runtime-v1");
    report.put("automatic_changes_applied", false);
    report.put("online_case_mutated", false);
    report.set("metric_scores", mapper.createObjectNode()
        .put("draft_approval_rate", 1.0)
        .put("reviewer_modification_rate", 0.0));
    report.set("findings", mapper.createArrayNode());
    return new EvaluationAgentResult(report, "production-runtime-java-deterministic", "production-runtime-v1", 0, 0);
  }
}

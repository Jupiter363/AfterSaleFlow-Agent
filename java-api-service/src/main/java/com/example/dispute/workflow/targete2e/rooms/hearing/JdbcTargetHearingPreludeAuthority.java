package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Freezes the exact pre-Hearing matrices into the formal COURT_PREPARING output and reloads only
 * that receipt-bound value for every later public introduction and Agent request.
 */
final class JdbcTargetHearingPreludeAuthority {

  static final String SCHEMA_VERSION = "hearing-prelude-authority.v1";

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  JdbcTargetHearingPreludeAuthority(DataSource dataSource, ObjectMapper mapper) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
  }

  Authority freeze(HearingRoomStart start) {
    Objects.requireNonNull(start, "start");
    ObjectNode intake = object(
        one(
            jdbc.query(
                """
                select dossier_json::text
                  from case_intake_dossier
                 where case_id = ? and room_type = 'INTAKE'
                 for update
                """,
                (row, ignored) -> parse(row.getString(1)),
                start.caseId()),
            "Target Hearing frozen Intake dossier is absent or ambiguous"),
        "Intake dossier");
    ObjectNode caseMatrix = object(intake.path("case_fact_matrix"), "case fact matrix");
    ObjectNode evidenceDossier = object(
        one(
            jdbc.query(
                """
                select jsonb_build_object(
                         'dossier_id', id,
                         'dossier_version', dossier_version,
                         'dossier_status', dossier_status,
                         'fact_evidence_matrix', matrix_summary_json -> 'fact_evidence_matrix',
                         'evidence_summary', summary_json
                       )::text
                  from evidence_dossier
                 where case_id = ? and dossier_status = 'FROZEN' and deleted_at is null
                 order by dossier_version desc, id desc
                 limit 2
                 for update
                """,
                (row, ignored) -> parse(row.getString(1)),
                start.caseId()),
            "Target Hearing frozen Evidence dossier is absent or ambiguous"),
        "Evidence dossier");
    return authority(start, caseMatrix, evidenceDossier);
  }

  Authority loadCommitted(HearingRoomStart start) {
    Objects.requireNonNull(start, "start");
    CommittedPrelude committed = one(
        jdbc.query(
            """
            select stage.output_json::text, receipt.result_hash,
                   receipt.receipt_id, receipt.receipt_hash
              from hearing_flow_stage stage
              join hearing_domain_receipt receipt
                on receipt.flow_instance_id = stage.flow_instance_id
               and receipt.case_id = stage.case_id
               and receipt.operation_type = 'STAGE'
               and receipt.source_stage = 'COURT_PREPARING'
               and receipt.source_stage_sequence = 1
               and receipt.stage_code = 'CASE_INTRODUCTION'
               and receipt.stage_sequence = 2
               and receipt.result_ref = 'urn:hearing:stage:1:COURT_PREPARING'
             where stage.flow_instance_id = ? and stage.case_id = ?
               and stage.stage_code = 'COURT_PREPARING' and stage.stage_sequence = 1
               and stage.stage_status = 'COMPLETED'
               and receipt.tenant_surrogate = ? and receipt.epoch_id = ?
               and receipt.hearing_epoch = ? and receipt.writer_mode = 'TEMPORAL'
               and receipt.fencing_token = ?
             for update of stage, receipt
            """,
            (row, ignored) -> new CommittedPrelude(
                object(parse(row.getString(1)), "committed prelude"),
                row.getString(2),
                row.getString(3),
                row.getString(4)),
            start.flowInstanceId(),
            start.caseId(),
            start.tenantSurrogate(),
            start.epochId(),
            start.roomEpoch(),
            start.fencingToken()),
        "Target Hearing committed prelude authority is absent or ambiguous");
    Authority authority = authorityFromPayload(start, committed.payload());
    require(
        authority.contentHash().equals(committed.resultHash()),
        "Target Hearing committed prelude hash is not its formal receipt result");
    require(
        committed.receiptId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
            && committed.receiptHash().matches("[0-9a-f]{64}"),
        "Target Hearing committed prelude receipt is invalid");
    return authority;
  }

  private Authority authority(
      HearingRoomStart start, ObjectNode caseMatrix, ObjectNode evidenceDossier) {
    validateMatrices(start, caseMatrix, evidenceDossier);
    ObjectNode payload = mapper.createObjectNode();
    payload.put("schema_version", SCHEMA_VERSION);
    payload.put("tenant_surrogate", start.tenantSurrogate());
    payload.put("case_id", start.caseId());
    payload.put("flow_instance_id", start.flowInstanceId());
    payload.put("epoch_id", start.epochId());
    payload.put("room_epoch", start.roomEpoch());
    payload.put("fencing_token", start.fencingToken());
    payload.set("case_fact_matrix", caseMatrix.deepCopy());
    payload.set("evidence_dossier", evidenceDossier.deepCopy());
    return new Authority(
        payload,
        caseMatrix.deepCopy(),
        evidenceDossier.deepCopy(),
        ContractJson.sha256Hex(payload));
  }

  private Authority authorityFromPayload(HearingRoomStart start, ObjectNode payload) {
    require(
        SCHEMA_VERSION.equals(payload.path("schema_version").asText())
            && start.tenantSurrogate().equals(payload.path("tenant_surrogate").asText())
            && start.caseId().equals(payload.path("case_id").asText())
            && start.flowInstanceId().equals(payload.path("flow_instance_id").asText())
            && start.epochId().equals(payload.path("epoch_id").asText())
            && start.roomEpoch() == payload.path("room_epoch").asLong(-1)
            && start.fencingToken() == payload.path("fencing_token").asLong(-1),
        "Target Hearing prelude coordinates are invalid");
    return authority(
        start,
        object(payload.path("case_fact_matrix"), "prelude case fact matrix"),
        object(payload.path("evidence_dossier"), "prelude evidence dossier"));
  }

  private void validateMatrices(
      HearingRoomStart start, ObjectNode caseMatrix, ObjectNode evidenceDossier) {
    require(
        "case_fact_matrix.v2".equals(caseMatrix.path("schema_version").asText())
            && start.caseId().equals(caseMatrix.path("case_id").asText())
            && caseMatrix.path("matrix_id").isTextual()
            && !caseMatrix.path("matrix_id").asText().isBlank()
            && caseMatrix.path("matrix_version").asInt(0) >= 1
            && caseMatrix.path("fact_rows").isArray()
            && caseMatrix.path("content_hash").asText().matches("[0-9a-f]{64}")
            && caseMatrix.path("content_hash").asText().equals(
                javaOwnedContentHash(caseMatrix, "content_hash")),
        "Target Hearing frozen case matrix is invalid");
    ObjectNode evidenceMatrix =
        object(evidenceDossier.path("fact_evidence_matrix"), "fact evidence matrix");
    require(
        evidenceDossier.path("dossier_id").isTextual()
            && !evidenceDossier.path("dossier_id").asText().isBlank()
            && evidenceDossier.path("dossier_version").asInt(0) >= 1
            && "FROZEN".equals(evidenceDossier.path("dossier_status").asText())
            && "fact_evidence_matrix.v3".equals(evidenceMatrix.path("schema_version").asText())
            && start.caseId().equals(evidenceMatrix.path("case_id").asText())
            && "FROZEN".equals(evidenceMatrix.path("matrix_status").asText())
            && evidenceMatrix.path("matrix_id").isTextual()
            && !evidenceMatrix.path("matrix_id").asText().isBlank()
            && evidenceMatrix.path("matrix_version").asInt(0) >= 1
            && evidenceMatrix.path("fact_coverage").isArray()
            && evidenceMatrix.path("links").isArray()
            && evidenceMatrix.path("source_refs").isArray()
            && caseMatrix.path("matrix_id").asText()
                .equals(evidenceMatrix.path("case_fact_matrix_id").asText())
            && caseMatrix.path("matrix_version").asInt()
                == evidenceMatrix.path("case_fact_matrix_version").asInt()
            && caseMatrix.path("content_hash").asText()
                .equals(evidenceMatrix.path("case_fact_matrix_hash").asText())
            && evidenceMatrix.path("content_hash").asText().matches("[0-9a-f]{64}")
            && evidenceMatrix.path("content_hash").asText().equals(
                JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
                    mapper, evidenceMatrix, "content_hash")),
        "Target Hearing frozen evidence matrix is invalid");
  }

  private JsonNode parse(String value) {
    try {
      JsonNode parsed = mapper.readTree(value);
      if (parsed == null) {
        throw new IllegalStateException("Target Hearing prelude source is null JSON");
      }
      return parsed;
    } catch (Exception failure) {
      throw new IllegalStateException("Target Hearing prelude source is invalid JSON", failure);
    }
  }

  private static String javaOwnedContentHash(ObjectNode value, String field) {
    ObjectNode unsigned = value.deepCopy();
    unsigned.remove(field);
    return ContractJson.sha256Hex(unsigned);
  }

  private static ObjectNode object(JsonNode value, String label) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalStateException("Target Hearing " + label + " is not an object");
    }
    return object;
  }

  private static <T> T one(List<T> rows, String message) {
    if (rows.size() != 1) {
      throw new IllegalStateException(message);
    }
    return rows.getFirst();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record Authority(
      ObjectNode payload,
      ObjectNode caseFactMatrix,
      ObjectNode evidenceDossier,
      String contentHash) {
    Authority {
      payload = Objects.requireNonNull(payload, "payload").deepCopy();
      caseFactMatrix = Objects.requireNonNull(caseFactMatrix, "caseFactMatrix").deepCopy();
      evidenceDossier = Objects.requireNonNull(evidenceDossier, "evidenceDossier").deepCopy();
      if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("prelude contentHash must be lowercase SHA-256");
      }
    }

    ObjectNode evidenceMatrix() {
      return object(evidenceDossier.path("fact_evidence_matrix"), "fact evidence matrix");
    }
  }

  private record CommittedPrelude(
      ObjectNode payload, String resultHash, String receiptId, String receiptHash) {}
}

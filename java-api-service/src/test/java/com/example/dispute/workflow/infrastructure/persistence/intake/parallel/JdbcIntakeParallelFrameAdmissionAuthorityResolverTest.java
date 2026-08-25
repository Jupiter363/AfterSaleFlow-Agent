package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.StagingConflictException;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcIntakeParallelFrameAdmissionAuthorityResolverTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void bindsTheCommandRoomIdToTheRunRoomIdInsteadOfTheEpochIdentity() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(
                        argThat(sql -> sql.contains("run.room_id as run_room_id")),
                        any(SqlParameterSource.class)))
                .thenReturn(List.of(authorityRow(request, request.command().roomId())));

        var authority =
                new JdbcIntakeParallelFrameAdmissionAuthorityResolver(jdbc, mapper).resolve(request);

        assertThat(authority.fencingToken()).isEqualTo(11L);
        assertThat(authority.eventAuthority().eventBindingId()).isEqualTo("EVENT_BINDING_1");
    }

    @Test
    void rejectsAnEpochIdentityEvenWhenItIsMistakenForTheRunRoomId() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(
                        argThat(sql -> sql.contains("run.room_id as run_room_id")),
                        any(SqlParameterSource.class)))
                .thenReturn(List.of(authorityRow(request, "CRE_DISTINCT_EPOCH_ID")));

        assertThatThrownBy(
                        () -> new JdbcIntakeParallelFrameAdmissionAuthorityResolver(jdbc, mapper)
                                .resolve(request))
                .isInstanceOfSatisfying(
                        StagingConflictException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("INTAKE_PARALLEL_ADMISSION_AUTHORITY_DRIFT"));
    }

    private Map<String, Object> authorityRow(
            ExecuteAgentRunRequest request, String persistedRunRoomId) {
        var command = request.command();
        var event = command.eventRef();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("command_id", command.commandId());
        row.put("command_request_hash", command.requestHash());
        row.put("attempt_status", "RUNNING");
        row.put("model_profile_id", command.invocationContext().modelProfileId());
        row.put("protocol", "agent-stream.v4");
        row.put("run_status", "RUNNING");
        row.put("finalization_status", "UNCOMMITTED");
        row.put("run_tenant_surrogate", command.tenantSurrogate());
        row.put("run_case_id", command.caseId());
        row.put("run_room_id", persistedRunRoomId);
        row.put("run_room_epoch", command.roomEpoch());
        row.put("run_fencing_token", 11L);
        row.put("binding_id", "EVENT_BINDING_1");
        row.put("thread_registration_id", "THREAD_REGISTRATION_1");
        row.put("event_sequence", 4L);
        row.put("binding_generation", 2L);
        row.put("tenant_surrogate", command.tenantSurrogate());
        row.put("case_id", command.caseId());
        row.put("room_epoch", command.roomEpoch());
        row.put("fencing_token", 11L);
        row.put("thread_id", command.threadId());
        row.put(
                "actor_scope_hash",
                ContractJson.sha256Hex(mapper.valueToTree(command.actorScope())));
        row.put("agent_session_id", "AGENT_SESSION_1");
        row.put("binding_type", "EVENT");
        row.put("schema_version", event.schemaVersion());
        row.put("artifact_id", event.artifactId());
        row.put("object_uri", event.uri());
        row.put("content_sha256", event.sha256());
        row.put("size_bytes", event.sizeBytes());
        row.put("current_binding_id", "EVENT_BINDING_1");
        row.put("current_generation", 2L);
        row.put("authority_version", 0L);
        return row;
    }
}

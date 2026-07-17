package com.example.dispute.workflow.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Visibility;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ManifestTerminalStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.OperationStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.OutboxStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.AgentExecutionManifestRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandOutboxRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.DomainOperationRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.ImmutablePayloadSnapshotRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.ProcessReconciliationIssueRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Testcontainers
class WorkflowPersistenceRepositoryIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "workflow_persistence")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/workflow_persistence");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private CaseCommandRepository commandRepository;
    @Autowired private CaseCommandOutboxRepository outboxRepository;
    @Autowired private CaseProcessProjectionRepository projectionRepository;
    @Autowired private CaseRoomEpochRepository roomEpochRepository;
    @Autowired private DomainOperationRepository operationRepository;
    @Autowired private ProcessReconciliationIssueRepository issueRepository;
    @Autowired private ImmutablePayloadSnapshotRepository snapshotRepository;
    @Autowired private AgentExecutionManifestRepository manifestRepository;

    @Test
    void readsTypedControlPlaneRecordsFromTheV040Schema() {
        insertFixture();
        entityManager.clear();

        assertThat(commandRepository.findByTenantSurrogateAndCommandId("tenant-repo", "command-repo"))
                .hasValueSatisfying(
                        command -> {
                            assertThat(command.getCaseCommandSequence()).isEqualTo(1);
                            assertThat(command.getCommandStatus())
                                    .isEqualTo(CommandStatus.PENDING_ORCHESTRATION);
                        });
        assertThat(outboxRepository.findByCaseCommandId("CMD_REPO"))
                .hasValueSatisfying(
                        outbox -> assertThat(outbox.getOutboxStatus()).isEqualTo(OutboxStatus.PENDING));
        assertThat(projectionRepository.findById("CASE_REPO_CONTROL"))
                .hasValueSatisfying(
                        projection -> {
                            assertThat(projection.getWriterMode()).isEqualTo(WriterMode.LEGACY);
                            assertThat(projection.getTemporalWorkflowId()).isNull();
                        });
        assertThat(
                        roomEpochRepository.findByCaseIdAndRoomTypeAndLifecycleStatus(
                                "CASE_REPO_CONTROL", RoomType.EVIDENCE, EpochLifecycleStatus.ACTIVE))
                .hasValueSatisfying(
                        epoch -> {
                            assertThat(epoch.getRoomEpoch()).isZero();
                            assertThat(epoch.getFencingToken()).isZero();
                        });
        assertThat(operationRepository.findByTenantSurrogateAndOperationKey("tenant-repo", "operation-repo"))
                .hasValueSatisfying(
                        operation -> assertThat(operation.getOperationStatus()).isEqualTo(OperationStatus.STARTED));
        assertThat(issueRepository.findByTenantSurrogateAndIssueKey("tenant-repo", "issue-repo"))
                .hasValueSatisfying(
                        issue -> assertThat(issue.getIssueStatus()).isEqualTo(ReconciliationStatus.OPEN));
        assertThat(
                        snapshotRepository.findByTenantSurrogateAndCaseIdAndContentSha256(
                                "tenant-repo", "CASE_REPO_CONTROL", "c".repeat(64)))
                .hasValueSatisfying(
                        snapshot -> assertThat(snapshot.getVisibility()).isEqualTo(Visibility.PARTIES));
        assertThat(
                        manifestRepository.findByTenantSurrogateAndCaseIdAndLogicalAgentRunId(
                                "tenant-repo", "CASE_REPO_CONTROL", "logical-run-repo"))
                .hasValueSatisfying(
                        manifest ->
                                assertThat(manifest.getTerminalStatus())
                                        .isEqualTo(ManifestTerminalStatus.COMPLETED));
    }

    private void insertFixture() {
        jdbc.execute(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level,
                    title, description, current_room, created_by, updated_by
                ) values (
                    'CASE_REPO_CONTROL', 'user-repo', 'merchant-repo',
                    'repo-control-idempotency', 'DISPUTE', 'EVIDENCE_OPEN',
                    'USER', 'user-repo', 'MERCHANT', 'merchant-repo', 'HIGH',
                    'Repository control case', 'JPA mapping fixture.',
                    'EVIDENCE', 'repository-test', 'repository-test'
                );

                insert into case_room (
                    id, case_id, room_type, room_status, opened_at,
                    created_by, updated_by
                ) values (
                    'ROOM_REPO_CONTROL', 'CASE_REPO_CONTROL', 'EVIDENCE',
                    'OPEN', now(), 'repository-test', 'repository-test'
                );

                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase,
                    writer_mode, process_revision, room_epoch, fencing_token
                ) values (
                    'CASE_REPO_CONTROL', 'tenant-repo', 'EVIDENCE_OPEN',
                    'EVIDENCE', 'OPEN', 'LEGACY', 0, 0, 0
                );

                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, process_revision, room_revision,
                    fencing_token, stream_protocol, activated_at
                ) values (
                    'EPOCH_REPO', 'tenant-repo', 'CASE_REPO_CONTROL',
                    'ROOM_REPO_CONTROL', 'EVIDENCE', 0, 'LEGACY', 'ACTIVE',
                    0, 0, 0, 'agent_stream.v1', now()
                );

                insert into case_command (
                    id, command_id, tenant_surrogate, case_id,
                    case_command_sequence, command_type, room_type, room_epoch,
                    actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status
                ) values (
                    'CMD_REPO', 'command-repo', 'tenant-repo',
                    'CASE_REPO_CONTROL', 1, 'EVIDENCE_SUBMIT', 'EVIDENCE', 0,
                    'user-repo', 'USER', '["case:write"]',
                    'evidence-command.v1', 'urn:payload:command-repo',
                    repeat('a', 64), 12, 0, now(), now() + interval '5 minutes',
                    '00-0123456789abcdef0123456789abcdef-0123456789abcdef-01',
                    repeat('b', 64), 'PENDING_ORCHESTRATION'
                );

                insert into case_command_outbox (
                    id, case_command_id, tenant_surrogate, case_id,
                    workflow_id, workflow_type, task_queue, delivery_kind,
                    update_id, outbox_status, available_at
                ) values (
                    'OUTBOX_REPO', 'CMD_REPO', 'tenant-repo', 'CASE_REPO_CONTROL',
                    'case:CASE_REPO_CONTROL', 'CaseProcessWorkflow',
                    'case-control', 'UPDATE_WITH_START', 'command-repo',
                    'PENDING', now()
                );

                insert into domain_operation (
                    id, operation_key, tenant_surrogate, case_id, case_command_id,
                    operation_type, room_type, room_epoch, process_revision,
                    fencing_token, request_hash, operation_status, started_at
                ) values (
                    'OP_REPO', 'operation-repo', 'tenant-repo', 'CASE_REPO_CONTROL',
                    'CMD_REPO', 'PROJECT_CASE', 'EVIDENCE', 0, 0, 0,
                    repeat('b', 64), 'STARTED', now()
                );

                insert into process_reconciliation_issue (
                    id, issue_key, tenant_surrogate, case_id, issue_type,
                    issue_scope, severity, issue_status, room_type, room_epoch,
                    process_revision, fencing_token, details_json, detected_at
                ) values (
                    'ISSUE_REPO', 'issue-repo', 'tenant-repo', 'CASE_REPO_CONTROL',
                    'SHADOW_PHASE_DRIFT', 'SHADOW', 'WARNING', 'OPEN',
                    'EVIDENCE', 0, 0, 0, '{"field":"room_phase"}', now()
                );

                insert into immutable_payload_snapshot (
                    id, tenant_surrogate, case_id, room_type, snapshot_type,
                    source_type, source_id, schema_version, object_uri,
                    content_sha256, size_bytes, visibility, created_by
                ) values (
                    'SNAPSHOT_REPO', 'tenant-repo', 'CASE_REPO_CONTROL',
                    'EVIDENCE', 'COMMAND_INPUT', 'CASE_COMMAND', 'CMD_REPO',
                    'evidence-command.v1', 'urn:payload:command-repo',
                    repeat('c', 64), 12, 'PARTIES', 'repository-test'
                );

                insert into agent_execution_manifest (
                    id, schema_version, tenant_surrogate, case_id, room_type,
                    room_epoch, process_revision, fencing_token,
                    logical_agent_run_id, manifest_uri, manifest_sha256,
                    output_snapshot_id, output_sha256, terminal_status, finalized_at
                ) values (
                    'MANIFEST_REPO', 'agent-execution-manifest.v1',
                    'tenant-repo', 'CASE_REPO_CONTROL', 'EVIDENCE', 0, 0, 0,
                    'logical-run-repo', 'urn:manifest:logical-run-repo',
                    repeat('d', 64), 'SNAPSHOT_REPO', repeat('c', 64),
                    'COMPLETED', now()
                );
                """);
    }
}

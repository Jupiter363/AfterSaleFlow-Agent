package com.example.dispute.workflow.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.infrastructure.persistence.entity.AgentExecutionManifestEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandOutboxEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.DomainOperationEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.ImmutablePayloadSnapshotEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.ProcessReconciliationIssueEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.AgentExecutionManifestRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandOutboxRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.DomainOperationRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.ImmutablePayloadSnapshotRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.ProcessReconciliationIssueRepository;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

class WorkflowPersistenceModelContractTest {

    @Test
    void mapsEveryControlPlaneTableBehindTheWorkflowPersistenceBoundary() {
        Map<Class<?>, String> mappings =
                Map.of(
                        CaseCommandEntity.class, "case_command",
                        CaseCommandOutboxEntity.class, "case_command_outbox",
                        CaseProcessProjectionEntity.class, "case_process_projection",
                        CaseRoomEpochEntity.class, "case_room_epoch",
                        DomainOperationEntity.class, "domain_operation",
                        ProcessReconciliationIssueEntity.class, "process_reconciliation_issue",
                        ImmutablePayloadSnapshotEntity.class, "immutable_payload_snapshot",
                        AgentExecutionManifestEntity.class, "agent_execution_manifest");

        mappings.forEach(
                (type, table) -> {
                    assertThat(type).hasAnnotation(Entity.class);
                    assertThat(type.getAnnotation(Table.class).name()).isEqualTo(table);
                });

        assertThat(ImmutablePayloadSnapshotEntity.class).hasAnnotation(Immutable.class);
        assertThat(AgentExecutionManifestEntity.class).hasAnnotation(Immutable.class);
    }

    @Test
    void exposesSpringDataRepositoriesForEveryControlPlaneRecord() {
        assertThat(
                        new Class<?>[] {
                            CaseCommandRepository.class,
                            CaseCommandOutboxRepository.class,
                            CaseProcessProjectionRepository.class,
                            CaseRoomEpochRepository.class,
                            DomainOperationRepository.class,
                            ProcessReconciliationIssueRepository.class,
                            ImmutablePayloadSnapshotRepository.class,
                            AgentExecutionManifestRepository.class
                        })
                .allMatch(JpaRepository.class::isAssignableFrom);
    }
}

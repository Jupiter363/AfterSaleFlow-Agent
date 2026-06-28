package com.example.dispute.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.dispute.audit.application.AuditLogView;
import com.example.dispute.audit.application.AuditQueryService;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditQueryServiceTest {

    @Mock private AuditLogRepository auditRepository;
    @Mock private FulfillmentCaseRepository caseRepository;

    private AuditQueryService service;

    @BeforeEach
    void setUp() {
        service =
                new AuditQueryService(
                        auditRepository,
                        caseRepository,
                        new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void reviewerCanReadStructuredCaseAuditLogs() {
        AuditLogEntity entity =
                AuditLogEntity.record(
                        "AUDIT_1",
                        "CASE_audit",
                        "TRACE_1",
                        "REQUEST_1",
                        "reviewer-1",
                        "PLATFORM_REVIEWER",
                        "REVIEW_APPROVED",
                        "REVIEW_TASK",
                        "REVIEW_1",
                        "{}",
                        "{\"decision\":\"APPROVE\"}");
        when(caseRepository.existsById("CASE_audit")).thenReturn(true);
        when(auditRepository.findAllByCaseIdOrderByCreatedAtDesc("CASE_audit"))
                .thenReturn(List.of(entity));

        List<AuditLogView> result =
                service.listForCase(
                        "CASE_audit",
                        new AuthenticatedActor(
                                "reviewer-1", ActorRole.PLATFORM_REVIEWER));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().action()).isEqualTo("REVIEW_APPROVED");
        assertThat(result.getFirst().after().path("decision").asText())
                .isEqualTo("APPROVE");
    }

    @Test
    void partyCannotReadInternalAuditTrail() {
        assertThatThrownBy(
                        () ->
                                service.listForCase(
                                        "CASE_audit",
                                        new AuthenticatedActor(
                                                "user-1", ActorRole.USER)))
                .isInstanceOf(ForbiddenException.class);
    }
}

package com.example.dispute.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.common.api.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExceptionHierarchyTest {

    @Test
    void typedExceptionsCarryStableCodesAndImmutableDetails() {
        BadRequestException exception =
                new BadRequestException("invalid request", Map.of("field", "order_id"));

        assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(exception.details()).containsEntry("field", "order_id");
        assertThatThrownBy(() -> exception.details().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiredExceptionTypesShareTheBusinessExceptionContract() {
        List<BusinessException> exceptions =
                List.of(
                        new UnauthorizedException("authentication required"),
                        new ForbiddenException("access denied"),
                        new NotFoundException(
                                ErrorCode.CASE_NOT_FOUND,
                                "case not found",
                                Map.of("case_id", "CASE_404")),
                        new IdempotencyConflictException("duplicate request"),
                        new ExternalServiceException(
                                ErrorCode.AGENT_SERVICE_UNAVAILABLE,
                                "agent unavailable",
                                Map.of()),
                        new DatabaseException("database unavailable", new RuntimeException("db")),
                        new WorkflowExecutionException(
                                ErrorCode.WORKFLOW_START_FAILED,
                                "workflow failed",
                                Map.of()),
                        new AgentExecutionException(
                                ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                                "schema invalid",
                                Map.of()),
                        new ToolExecutionException(
                                ErrorCode.TOOL_EXECUTION_FAILED,
                                "tool failed",
                                Map.of()),
                        new TimeoutException("external timeout"),
                        new ApprovalException("approval required"));

        assertThat(exceptions).allMatch(exception -> exception.errorCode() != null);
        assertThat(exceptions).allMatch(exception -> exception.getMessage() != null);
    }
}

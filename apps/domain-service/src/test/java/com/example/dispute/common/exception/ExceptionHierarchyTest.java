/*
 * 所属模块：后端公共边界。
 * 文件职责：验证Hierarchy，覆盖 「typedExceptionsCarryStableCodesAndImmutableDetails」、「requiredExceptionTypesShareTheBusinessExceptionContract」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.common.api.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// 所属模块：【后端公共边界 / 自动化测试层】类型「ExceptionHierarchyTest」。
// 类型职责：集中验证Hierarchy的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「typedExceptionsCarryStableCodesAndImmutableDetails」、「requiredExceptionTypesShareTheBusinessExceptionContract」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class ExceptionHierarchyTest {

    // 所属模块：【后端公共边界 / 自动化测试层】「ExceptionHierarchyTest.typedExceptionsCarryStableCodesAndImmutableDetails()」。
    // 具体功能：「ExceptionHierarchyTest.typedExceptionsCarryStableCodesAndImmutableDetails()」：复现“核对完整业务行为（场景方法「typedExceptionsCarryStableCodesAndImmutableDetails」）”场景：驱动 「exception.errorCode」、「exception.details」、「assertThat(exception.errorCode()).isEqualTo」、「assertThat(exception.details()).containsEntry」，再用 「assertThat」、「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「field」、「order_id」、「other」、「value」。
    // 上游调用：「ExceptionHierarchyTest.typedExceptionsCarryStableCodesAndImmutableDetails()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ExceptionHierarchyTest.typedExceptionsCarryStableCodesAndImmutableDetails()」的下游是被测服务、仓储或外部客户端替身；「assertThat、assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ExceptionHierarchyTest.typedExceptionsCarryStableCodesAndImmutableDetails()」守住「后端公共边界」的可执行规格，尤其防止 「field」、「order_id」、「other」、「value」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void typedExceptionsCarryStableCodesAndImmutableDetails() {
        BadRequestException exception =
                new BadRequestException("invalid request", Map.of("field", "order_id"));

        assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(exception.details()).containsEntry("field", "order_id");
        assertThatThrownBy(() -> exception.details().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // 所属模块：【后端公共边界 / 自动化测试层】「ExceptionHierarchyTest.requiredExceptionTypesShareTheBusinessExceptionContract()」。
    // 具体功能：「ExceptionHierarchyTest.requiredExceptionTypesShareTheBusinessExceptionContract()」：复现“核对完整业务行为（场景方法「requiredExceptionTypesShareTheBusinessExceptionContract」）”场景：驱动 「exception.errorCode」、「assertThat(exceptions).allMatch」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「case_id」、「CASE_404」、「db」。
    // 上游调用：「ExceptionHierarchyTest.requiredExceptionTypesShareTheBusinessExceptionContract()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ExceptionHierarchyTest.requiredExceptionTypesShareTheBusinessExceptionContract()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ExceptionHierarchyTest.requiredExceptionTypesShareTheBusinessExceptionContract()」守住「后端公共边界」的可执行规格，尤其防止 「case_id」、「CASE_404」、「db」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

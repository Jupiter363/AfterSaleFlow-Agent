/*
 * 所属模块：后端公共边界。
 * 文件职责：验证错误代码，覆盖 「requiredCodesHaveStableHttpStatusMappings」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

// 所属模块：【后端公共边界 / HTTP 接口层】类型「ErrorCodeTest」。
// 类型职责：集中验证错误代码的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「requiredCodesHaveStableHttpStatusMappings」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class ErrorCodeTest {

    // 所属模块：【后端公共边界 / HTTP 接口层】「ErrorCodeTest.requiredCodesHaveStableHttpStatusMappings()」。
    // 具体功能：「ErrorCodeTest.requiredCodesHaveStableHttpStatusMappings()」：复现“核对完整业务行为（场景方法「requiredCodesHaveStableHttpStatusMappings」）”场景：驱动 「Map.ofEntries」、「Map.entry」、「ErrorCode.values」、「expected.keySet」，再用 「assertThat」 核对返回值、状态变化或协作者调用。
    // 上游调用：「ErrorCodeTest.requiredCodesHaveStableHttpStatusMappings()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ErrorCodeTest.requiredCodesHaveStableHttpStatusMappings()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ErrorCodeTest.requiredCodesHaveStableHttpStatusMappings()」守住「后端公共边界」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    @Test
    void requiredCodesHaveStableHttpStatusMappings() {
        Map<ErrorCode, HttpStatus> expected =
                Map.ofEntries(
                        Map.entry(ErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST),
                        Map.entry(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED),
                        Map.entry(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN),
                        Map.entry(ErrorCode.CASE_NOT_FOUND, HttpStatus.NOT_FOUND),
                        Map.entry(ErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND),
                        Map.entry(ErrorCode.EVIDENCE_NOT_FOUND, HttpStatus.NOT_FOUND),
                        Map.entry(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND),
                        Map.entry(ErrorCode.CASE_STATUS_INVALID, HttpStatus.CONFLICT),
                        Map.entry(ErrorCode.CASE_DUPLICATED, HttpStatus.CONFLICT),
                        Map.entry(ErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT),
                        Map.entry(
                                ErrorCode.EVIDENCE_UPLOAD_FAILED,
                                HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(
                                ErrorCode.EVIDENCE_PARSE_FAILED,
                                HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(
                                ErrorCode.AGENT_SERVICE_UNAVAILABLE,
                                HttpStatus.SERVICE_UNAVAILABLE),
                        Map.entry(
                                ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                                HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(
                                ErrorCode.WORKFLOW_START_FAILED,
                                HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(
                                ErrorCode.WORKFLOW_SIGNAL_FAILED,
                                HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(ErrorCode.APPROVAL_REQUIRED, HttpStatus.CONFLICT),
                        Map.entry(ErrorCode.TOOL_EXECUTION_DENIED, HttpStatus.FORBIDDEN),
                        Map.entry(
                                ErrorCode.TOOL_EXECUTION_FAILED,
                                HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(
                                ErrorCode.EXTERNAL_SERVICE_TIMEOUT,
                                HttpStatus.GATEWAY_TIMEOUT),
                        Map.entry(ErrorCode.DATABASE_ERROR, HttpStatus.INTERNAL_SERVER_ERROR),
                        Map.entry(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(ErrorCode.values()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach(
                (errorCode, status) -> assertThat(errorCode.httpStatus()).isEqualTo(status));
    }
}

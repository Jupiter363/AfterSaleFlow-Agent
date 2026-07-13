/*
 * 所属模块：后端公共边界。
 * 文件职责：验证Api响应，覆盖 「successCarriesCorrelationIdentifiersAndPayload」、「failureCarriesStableErrorCodeAndDetails」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

// 所属模块：【后端公共边界 / HTTP 接口层】类型「ApiResponseTest」。
// 类型职责：集中验证Api响应的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「successCarriesCorrelationIdentifiersAndPayload」、「failureCarriesStableErrorCodeAndDetails」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class ApiResponseTest {

    // 所属模块：【后端公共边界 / HTTP 接口层】「ApiResponseTest.successCarriesCorrelationIdentifiersAndPayload()」。
    // 具体功能：「ApiResponseTest.successCarriesCorrelationIdentifiersAndPayload()」：复现“核对完整业务行为（场景方法「successCarriesCorrelationIdentifiersAndPayload」）”场景：驱动 「ApiResponse.success」、「Instant.parse」、「response.success」、「response.code」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「case_id」、「CASE_001」、「REQ_001」、「TRACE_001」。
    // 上游调用：「ApiResponseTest.successCarriesCorrelationIdentifiersAndPayload()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ApiResponseTest.successCarriesCorrelationIdentifiersAndPayload()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ApiResponseTest.successCarriesCorrelationIdentifiersAndPayload()」守住「后端公共边界」的可执行规格，尤其防止 「case_id」、「CASE_001」、「REQ_001」、「TRACE_001」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void successCarriesCorrelationIdentifiersAndPayload() {
        ApiResponse<Map<String, String>> response =
                ApiResponse.success(
                        Map.of("case_id", "CASE_001"),
                        "REQ_001",
                        "TRACE_001",
                        Instant.parse("2026-06-28T10:00:00Z"));

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.data()).containsEntry("case_id", "CASE_001");
        assertThat(response.requestId()).isEqualTo("REQ_001");
        assertThat(response.traceId()).isEqualTo("TRACE_001");
        assertThat(response.timestamp()).isEqualTo(Instant.parse("2026-06-28T10:00:00Z"));
    }

    // 所属模块：【后端公共边界 / HTTP 接口层】「ApiResponseTest.failureCarriesStableErrorCodeAndDetails()」。
    // 具体功能：「ApiResponseTest.failureCarriesStableErrorCodeAndDetails()」：复现“核对完整业务行为（场景方法「failureCarriesStableErrorCodeAndDetails」）”场景：驱动 「ApiResponse.failure」、「Instant.parse」、「response.success」、「response.code」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「case_id」、「CASE_404」、「REQ_404」、「TRACE_404」。
    // 上游调用：「ApiResponseTest.failureCarriesStableErrorCodeAndDetails()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ApiResponseTest.failureCarriesStableErrorCodeAndDetails()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ApiResponseTest.failureCarriesStableErrorCodeAndDetails()」守住「后端公共边界」的可执行规格，尤其防止 「case_id」、「CASE_404」、「REQ_404」、「TRACE_404」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void failureCarriesStableErrorCodeAndDetails() {
        ApiResponse<Void> response =
                ApiResponse.failure(
                        ErrorCode.CASE_NOT_FOUND,
                        "case not found",
                        Map.of("case_id", "CASE_404"),
                        "REQ_404",
                        "TRACE_404",
                        Instant.parse("2026-06-28T10:00:00Z"));

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("CASE_NOT_FOUND");
        assertThat(response.message()).isEqualTo("case not found");
        assertThat(response.data()).isNull();
        assertThat(response.details()).containsEntry("case_id", "CASE_404");
    }
}

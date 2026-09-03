/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证内部证据，覆盖 「systemServiceCanReadAuthorizedOriginalWithJavaServiceSecret」、「rejectsWrongJavaServiceSecretBeforeReadingEvidence」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.api.InternalEvidenceController;
import com.example.dispute.evidence.application.EvidenceApplicationService;
import com.example.dispute.evidence.application.EvidenceContentView;
import com.example.dispute.evidence.application.EvidenceParseResultService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「InternalEvidenceControllerTest」。
// 类型职责：集中验证内部证据的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「systemServiceCanReadAuthorizedOriginalWithJavaServiceSecret」、「rejectsWrongJavaServiceSecretBeforeReadingEvidence」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class InternalEvidenceControllerTest {

    @Mock private EvidenceParseResultService parseResultService;
    @Mock private EvidenceApplicationService evidenceService;
    @Mock private AppProperties properties;
    @Mock private AppProperties.Security security;
    @Mock private Authentication authentication;

    private InternalEvidenceController controller;
    private AuthenticatedActor systemActor;

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「InternalEvidenceControllerTest.setUp()」。
    // 具体功能：「InternalEvidenceControllerTest.setUp()」：在每个测试场景运行前创建「Clock.systemUTC」、「properties.security」、「security.serviceSecret」、「when」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「InternalEvidenceControllerTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「InternalEvidenceControllerTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「InternalEvidenceControllerTest.setUp()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「java-service-secret」、「python-agent-service」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        when(properties.security()).thenReturn(security);
        when(security.serviceSecret()).thenReturn("java-service-secret");
        systemActor = new AuthenticatedActor("python-agent-service", ActorRole.SYSTEM);
        controller =
                new InternalEvidenceController(
                        parseResultService,
                        evidenceService,
                        properties,
                        Clock.systemUTC());
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「InternalEvidenceControllerTest.systemServiceCanReadAuthorizedOriginalWithJavaServiceSecret()」。
    // 具体功能：「InternalEvidenceControllerTest.systemServiceCanReadAuthorizedOriginalWithJavaServiceSecret()」：复现“核对完整业务行为（场景方法「systemServiceCanReadAuthorizedOriginalWithJavaServiceSecret」）”场景：驱动 「evidenceService.contentForModel」、「controller.content」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「image-bytes」、「CASE_1」、「EVIDENCE_1」、「proof.jpg」。
    // 上游调用：「InternalEvidenceControllerTest.systemServiceCanReadAuthorizedOriginalWithJavaServiceSecret()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「InternalEvidenceControllerTest.systemServiceCanReadAuthorizedOriginalWithJavaServiceSecret()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「InternalEvidenceControllerTest.systemServiceCanReadAuthorizedOriginalWithJavaServiceSecret()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「image-bytes」、「CASE_1」、「EVIDENCE_1」、「proof.jpg」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void systemServiceCanReadAuthorizedOriginalWithJavaServiceSecret() {
        byte[] bytes = "image-bytes".getBytes(StandardCharsets.UTF_8);
        when(authentication.getPrincipal()).thenReturn(systemActor);
        when(evidenceService.contentForModel("CASE_1", "EVIDENCE_1", systemActor))
                .thenReturn(new EvidenceContentView("proof.jpg", "image/jpeg", bytes));

        var response =
                controller.content(
                        "CASE_1",
                        "EVIDENCE_1",
                        "java-service-secret",
                        authentication);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(response.getBody()).isEqualTo(bytes);
        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("proof.jpg");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「InternalEvidenceControllerTest.rejectsWrongJavaServiceSecretBeforeReadingEvidence()」。
    // 具体功能：「InternalEvidenceControllerTest.rejectsWrongJavaServiceSecretBeforeReadingEvidence()」：复现“拒绝非法输入或越权操作（场景方法「rejectsWrongJavaServiceSecretBeforeReadingEvidence」）”场景：驱动 「controller.content」，再用 「assertThatThrownBy」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_1」、「EVIDENCE_1」、「wrong-secret」。
    // 上游调用：「InternalEvidenceControllerTest.rejectsWrongJavaServiceSecretBeforeReadingEvidence()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「InternalEvidenceControllerTest.rejectsWrongJavaServiceSecretBeforeReadingEvidence()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「InternalEvidenceControllerTest.rejectsWrongJavaServiceSecretBeforeReadingEvidence()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_1」、「EVIDENCE_1」、「wrong-secret」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsWrongJavaServiceSecretBeforeReadingEvidence() {
        assertThatThrownBy(
                        () ->
                                controller.content(
                                        "CASE_1",
                                        "EVIDENCE_1",
                                        "wrong-secret",
                                        authentication))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Java service credential");

        verify(evidenceService, never())
                .contentForModel(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }
}

/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证证据解析，覆盖 「persistsSuccessfulTextAndExtractionMetadata」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.application.EvidenceParseResultService;
import com.example.dispute.evidence.application.ParseResultCommand;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「EvidenceParseResultServiceTest」。
// 类型职责：集中验证证据解析的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「persistsSuccessfulTextAndExtractionMetadata」、「evidence」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class EvidenceParseResultServiceTest {

    @Mock private EvidenceItemRepository repository;
    @Mock private AuditRecorder auditRecorder;

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceParseResultServiceTest.persistsSuccessfulTextAndExtractionMetadata()」。
    // 具体功能：「EvidenceParseResultServiceTest.persistsSuccessfulTextAndExtractionMetadata()」：复现“持久化业务事实（场景方法「persistsSuccessfulTextAndExtractionMetadata」）”场景：驱动 「repository.findById」、「service.apply」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_result」、「SUCCEEDED」、「签收证明文字」、「engine」。
    // 上游调用：「EvidenceParseResultServiceTest.persistsSuccessfulTextAndExtractionMetadata()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceParseResultServiceTest.persistsSuccessfulTextAndExtractionMetadata()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceParseResultServiceTest.persistsSuccessfulTextAndExtractionMetadata()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_result」、「SUCCEEDED」、「签收证明文字」、「engine」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void persistsSuccessfulTextAndExtractionMetadata() {
        EvidenceItemEntity entity = evidence();
        when(repository.findById("EVIDENCE_result")).thenReturn(Optional.of(entity));
        EvidenceParseResultService service =
                new EvidenceParseResultService(
                        repository,
                        new ObjectMapper().findAndRegisterModules(),
                        auditRecorder);

        service.apply(
                "EVIDENCE_result",
                new ParseResultCommand(
                        "SUCCEEDED",
                        "签收证明文字",
                        Map.of("engine", "paddleocr"),
                        null),
                new AuthenticatedActor("ocr-parser-service", ActorRole.SYSTEM));

        assertThat(entity.getParseStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(entity.getParsedText()).isEqualTo("签收证明文字");
        assertThat(entity.getExtractionJson()).contains("paddleocr");
        verify(auditRecorder)
                .record(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("EVIDENCE_PARSED"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceParseResultServiceTest.evidence()」。
    // 具体功能：「EvidenceParseResultServiceTest.evidence()」：作为测试辅助方法为“核对完整业务行为（场景方法「evidence」）”组装或读取「EvidenceItemEntity.uploaded」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceParseResultServiceTest.evidence()」由本测试类中的 「EvidenceParseResultServiceTest.persistsSuccessfulTextAndExtractionMetadata」 调用。
    // 下游影响：「EvidenceParseResultServiceTest.evidence()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceParseResultServiceTest.evidence()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_result」、「CASE_result」、「DOSSIER_result」、「LOGISTICS_PROOF」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static EvidenceItemEntity evidence() {
        return EvidenceItemEntity.uploaded(
                "EVIDENCE_result",
                "CASE_result",
                "DOSSIER_result",
                "LOGISTICS_PROOF",
                "USER_UPLOAD",
                "USER",
                "user-result",
                "evidence-original",
                "CASE_result/EVIDENCE_result/proof.png",
                "hash-result",
                "proof.png",
                "image/png",
                8,
                "PARTIES",
                null);
    }
}

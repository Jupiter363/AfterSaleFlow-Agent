/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证证据卷宗Revision，覆盖 「secondRoundRevisionFailsClosedWhenExistingMatrixIsUnparseable」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.evidence.application.EvidenceDossierRevisionService;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「EvidenceDossierRevisionServiceTest」。
// 类型职责：集中验证证据卷宗Revision的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「secondRoundRevisionFailsClosedWhenExistingMatrixIsUnparseable」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class EvidenceDossierRevisionServiceTest {

    @Mock private EvidenceDossierRepository dossierRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CaseEventService eventService;

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceDossierRevisionServiceTest.secondRoundRevisionFailsClosedWhenExistingMatrixIsUnparseable()」。
    // 具体功能：「EvidenceDossierRevisionServiceTest.secondRoundRevisionFailsClosedWhenExistingMatrixIsUnparseable()」：复现“核对完整业务行为（场景方法「secondRoundRevisionFailsClosedWhenExistingMatrixIsUnparseable」）”场景：驱动 「dossierRepository.findTopByCaseIdOrderByDossierVersionDesc」、「service.reviseAfterRoundIfNeeded」，再用 「assertThatThrownBy」、「verify」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_BAD_MATRIX」、「EVIDENCE_DOSSIER_BAD_MATRIX_V1」、「evidence-clerk」、「{\"evidence_count\":1}」。
    // 上游调用：「EvidenceDossierRevisionServiceTest.secondRoundRevisionFailsClosedWhenExistingMatrixIsUnparseable()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceDossierRevisionServiceTest.secondRoundRevisionFailsClosedWhenExistingMatrixIsUnparseable()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verify、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceDossierRevisionServiceTest.secondRoundRevisionFailsClosedWhenExistingMatrixIsUnparseable()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_BAD_MATRIX」、「EVIDENCE_DOSSIER_BAD_MATRIX_V1」、「evidence-clerk」、「{\"evidence_count\":1}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void secondRoundRevisionFailsClosedWhenExistingMatrixIsUnparseable() {
        EvidenceDossierRevisionService service =
                new EvidenceDossierRevisionService(
                        dossierRepository,
                        roomRepository,
                        eventService,
                        new ObjectMapper());
        when(dossierRepository.findTopByCaseIdOrderByDossierVersionDesc("CASE_BAD_MATRIX"))
                .thenReturn(
                        Optional.of(
                                EvidenceDossierEntity.frozen(
                                        "EVIDENCE_DOSSIER_BAD_MATRIX_V1",
                                        "CASE_BAD_MATRIX",
                                        1,
                                        "evidence-clerk",
                                        "{\"evidence_count\":1}",
                                        "[]",
                                        "{not-json")));

        assertThatThrownBy(
                        () ->
                                service.reviseAfterRoundIfNeeded(
                                        "CASE_BAD_MATRIX",
                                        EvidenceDossierRevisionService.EVIDENCE_EXPLANATION_ROUND,
                                        List.of(),
                                        "evidence-clerk"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid evidence dossier latest matrix summary");

        verify(dossierRepository, never()).save(any());
        verifyNoInteractions(roomRepository, eventService);
    }
}

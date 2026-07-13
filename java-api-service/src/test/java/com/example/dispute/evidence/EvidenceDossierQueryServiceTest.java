/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证证据卷宗Query，覆盖 「latestReadsFrozenObjectShapedEvidenceMatrix」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.EvidenceDossierQueryService;
import com.example.dispute.evidence.application.FrozenEvidenceDossierView;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceDossierItemRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「EvidenceDossierQueryServiceTest」。
// 类型职责：集中验证证据卷宗Query的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「latestReadsFrozenObjectShapedEvidenceMatrix」、「frozenDossier」、「actor」、「caseEntity」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class EvidenceDossierQueryServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceDossierRepository dossierRepository;
    @Mock private EvidenceDossierItemRepository itemRepository;

    private EvidenceDossierQueryService service;

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceDossierQueryServiceTest.setUp()」。
    // 具体功能：「EvidenceDossierQueryServiceTest.setUp()」：在每个测试场景运行前创建「newObjectMapper().findAndRegisterModules」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「EvidenceDossierQueryServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「EvidenceDossierQueryServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceDossierQueryServiceTest.setUp()」守住「证据与版本化卷宗」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        service =
                new EvidenceDossierQueryService(
                        caseRepository,
                        dossierRepository,
                        itemRepository,
                        new ObjectMapper().findAndRegisterModules());
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceDossierQueryServiceTest.latestReadsFrozenObjectShapedEvidenceMatrix()」。
    // 具体功能：「EvidenceDossierQueryServiceTest.latestReadsFrozenObjectShapedEvidenceMatrix()」：复现“核对完整业务行为（场景方法「latestReadsFrozenObjectShapedEvidenceMatrix」）”场景：驱动 「caseRepository.findById」、「dossierRepository.findTopByCaseIdOrderByDossierVersionDesc」、「itemRepository.findAllByDossierIdOrderBySequenceNo」、「service.latest」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_evidence」、「DOSSIER_FROZEN」、「fact」、「物流显示已签收」。
    // 上游调用：「EvidenceDossierQueryServiceTest.latestReadsFrozenObjectShapedEvidenceMatrix()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceDossierQueryServiceTest.latestReadsFrozenObjectShapedEvidenceMatrix()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceDossierQueryServiceTest.latestReadsFrozenObjectShapedEvidenceMatrix()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「DOSSIER_FROZEN」、「fact」、「物流显示已签收」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void latestReadsFrozenObjectShapedEvidenceMatrix() {
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(caseEntity()));
        when(dossierRepository.findTopByCaseIdOrderByDossierVersionDesc("CASE_evidence"))
                .thenReturn(Optional.of(frozenDossier()));
        when(itemRepository.findAllByDossierIdOrderBySequenceNo("DOSSIER_FROZEN"))
                .thenReturn(List.of());

        FrozenEvidenceDossierView view = service.latest("CASE_evidence", actor());

        assertThat(view.matrix()).hasSize(1);
        assertThat(view.matrix().get(0).get("fact")).isEqualTo("物流显示已签收");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceDossierQueryServiceTest.frozenDossier()」。
    // 具体功能：「EvidenceDossierQueryServiceTest.frozenDossier()」：作为测试辅助方法为“核对完整业务行为（场景方法「frozenDossier」）”组装或读取「EvidenceDossierEntity.frozen」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceDossierQueryServiceTest.frozenDossier()」由本测试类中的 「EvidenceDossierQueryServiceTest.latestReadsFrozenObjectShapedEvidenceMatrix」 调用。
    // 下游影响：「EvidenceDossierQueryServiceTest.frozenDossier()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceDossierQueryServiceTest.frozenDossier()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「DOSSIER_FROZEN」、「CASE_evidence」、「system」、「{\"evidence_count\":1}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static EvidenceDossierEntity frozenDossier() {
        return EvidenceDossierEntity.frozen(
                "DOSSIER_FROZEN",
                "CASE_evidence",
                2,
                "system",
                "{\"evidence_count\":1}",
                "[]",
                """
                {
                  "fact_evidence_matrix": [
                    {
                      "fact_id": "FACT_SIGNED",
                      "fact": "物流显示已签收",
                      "supporting_evidence": ["EVIDENCE_LOGISTICS"]
                    }
                  ],
                  "unmapped_evidence": []
                }
                """);
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceDossierQueryServiceTest.actor()」。
    // 具体功能：「EvidenceDossierQueryServiceTest.actor()」：作为测试辅助方法为“核对完整业务行为（场景方法「actor」）”组装或读取「AuthenticatedActor」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「EvidenceDossierQueryServiceTest.actor()」由本测试类中的 「EvidenceDossierQueryServiceTest.latestReadsFrozenObjectShapedEvidenceMatrix」 调用。
    // 下游影响：「EvidenceDossierQueryServiceTest.actor()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceDossierQueryServiceTest.actor()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「user-evidence」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static AuthenticatedActor actor() {
        return new AuthenticatedActor("user-evidence", ActorRole.USER);
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceDossierQueryServiceTest.caseEntity()」。
    // 具体功能：「EvidenceDossierQueryServiceTest.caseEntity()」：作为测试辅助方法为“核对完整业务行为（场景方法「caseEntity」）”组装或读取「FulfillmentCaseEntity.create」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceDossierQueryServiceTest.caseEntity()」由本测试类中的 「EvidenceDossierQueryServiceTest.latestReadsFrozenObjectShapedEvidenceMatrix」 调用。
    // 下游影响：「EvidenceDossierQueryServiceTest.caseEntity()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceDossierQueryServiceTest.caseEntity()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「order-evidence」、「user-evidence」、「merchant-evidence」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity caseEntity() {
        return FulfillmentCaseEntity.create(
                "CASE_evidence",
                "order-evidence",
                null,
                "user-evidence",
                "merchant-evidence",
                "idem-evidence",
                "DISPUTE",
                "LOGISTICS_DISPUTE",
                "signed status is disputed",
                RiskLevel.HIGH,
                "user-evidence");
    }
}

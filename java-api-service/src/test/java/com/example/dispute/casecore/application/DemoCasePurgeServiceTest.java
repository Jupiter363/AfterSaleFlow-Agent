/*
 * 所属模块：案件核心与导入。
 * 文件职责：验证演示案件案件清理，覆盖 「rejectsEveryRoleExceptPlatformReviewer」、「returnsNotFoundWhenCaseDoesNotExist」、「rejectsARegularIntakeCase」、「rejectsARealExternalImport」、「purgesNewAndLegacySimulatedImports」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.casecore.domain.CaseSourceType;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

// 所属模块：【案件核心与导入 / 应用编排层】类型「DemoCasePurgeServiceTest」。
// 类型职责：集中验证演示案件案件清理的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「rejectsEveryRoleExceptPlatformReviewer」、「returnsNotFoundWhenCaseDoesNotExist」、「rejectsARegularIntakeCase」、「rejectsARealExternalImport」、「purgesNewAndLegacySimulatedImports」、「simulatedCase」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class DemoCasePurgeServiceTest {

    private final FulfillmentCaseRepository caseRepository =
            mock(FulfillmentCaseRepository.class);
    private final DemoCasePurgeStore purgeStore = mock(DemoCasePurgeStore.class);
    private final DemoCasePurgeService service =
            new DemoCasePurgeService(caseRepository, purgeStore);

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoCasePurgeServiceTest.rejectsEveryRoleExceptPlatformReviewer(AuthenticatedActor)」。
    // 具体功能：「DemoCasePurgeServiceTest.rejectsEveryRoleExceptPlatformReviewer(AuthenticatedActor)」：复现“拒绝非法输入或越权操作（场景方法「rejectsEveryRoleExceptPlatformReviewer」）”场景：驱动 「service.purge」，再用 「assertThatThrownBy」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_demo」。
    // 上游调用：「DemoCasePurgeServiceTest.rejectsEveryRoleExceptPlatformReviewer(AuthenticatedActor)」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DemoCasePurgeServiceTest.rejectsEveryRoleExceptPlatformReviewer(AuthenticatedActor)」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DemoCasePurgeServiceTest.rejectsEveryRoleExceptPlatformReviewer(AuthenticatedActor)」守住「案件核心与导入」的可执行规格，尤其防止 「CASE_demo」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @ParameterizedTest
    @MethodSource("nonReviewerActors")
    void rejectsEveryRoleExceptPlatformReviewer(AuthenticatedActor actor) {
        assertThatThrownBy(() -> service.purge("CASE_demo", actor))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("only the platform reviewer can delete cases");

        verifyNoInteractions(caseRepository, purgeStore);
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoCasePurgeServiceTest.returnsNotFoundWhenCaseDoesNotExist()」。
    // 具体功能：「DemoCasePurgeServiceTest.returnsNotFoundWhenCaseDoesNotExist()」：复现“返回正确投影（场景方法「returnsNotFoundWhenCaseDoesNotExist」）”场景：驱动 「caseRepository.findByIdForUpdate」、「service.purge」，再用 「assertThatThrownBy」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_missing」、「reviewer-local」。
    // 上游调用：「DemoCasePurgeServiceTest.returnsNotFoundWhenCaseDoesNotExist()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DemoCasePurgeServiceTest.returnsNotFoundWhenCaseDoesNotExist()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DemoCasePurgeServiceTest.returnsNotFoundWhenCaseDoesNotExist()」守住「案件核心与导入」的可执行规格，尤其防止 「CASE_missing」、「reviewer-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void returnsNotFoundWhenCaseDoesNotExist() {
        when(caseRepository.findByIdForUpdate("CASE_missing"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.purge(
                                        "CASE_missing",
                                        new AuthenticatedActor(
                                                "reviewer-local",
                                                ActorRole.PLATFORM_REVIEWER)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("case was not found");

        verifyNoInteractions(purgeStore);
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoCasePurgeServiceTest.rejectsARegularIntakeCase()」。
    // 具体功能：「DemoCasePurgeServiceTest.rejectsARegularIntakeCase()」：复现“拒绝非法输入或越权操作（场景方法「rejectsARegularIntakeCase」）”场景：驱动 「caseRepository.findByIdForUpdate」、「service.purge」，再用 「assertThatThrownBy」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_regular」、「reviewer-local」。
    // 上游调用：「DemoCasePurgeServiceTest.rejectsARegularIntakeCase()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DemoCasePurgeServiceTest.rejectsARegularIntakeCase()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DemoCasePurgeServiceTest.rejectsARegularIntakeCase()」守住「案件核心与导入」的可执行规格，尤其防止 「CASE_regular」、「reviewer-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void purgesAnIntakeCreatedCase() {
        FulfillmentCaseEntity disputeCase = simulatedCase(CaseSourceType.INTAKE_CREATED, null);
        when(caseRepository.findByIdForUpdate("CASE_regular"))
                .thenReturn(Optional.of(disputeCase));

        service.purge(
                "CASE_regular",
                new AuthenticatedActor(
                        "reviewer-local", ActorRole.PLATFORM_REVIEWER));

        verify(purgeStore)
                .purge(
                        "CASE_regular",
                        "reviewer-local",
                        ActorRole.PLATFORM_REVIEWER.name());
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoCasePurgeServiceTest.rejectsARealExternalImport()」。
    // 具体功能：「DemoCasePurgeServiceTest.rejectsARealExternalImport()」：复现“拒绝非法输入或越权操作（场景方法「rejectsARealExternalImport」）”场景：驱动 「caseRepository.findByIdForUpdate」、「service.purge」，再用 「assertThatThrownBy」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「OMS」、「CASE_external」、「reviewer-local」。
    // 上游调用：「DemoCasePurgeServiceTest.rejectsARealExternalImport()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DemoCasePurgeServiceTest.rejectsARealExternalImport()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DemoCasePurgeServiceTest.rejectsARealExternalImport()」守住「案件核心与导入」的可执行规格，尤其防止 「OMS」、「CASE_external」、「reviewer-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsARealExternalImport() {
        FulfillmentCaseEntity disputeCase = simulatedCase(CaseSourceType.EXTERNAL_IMPORT, "OMS");
        when(caseRepository.findByIdForUpdate("CASE_external"))
                .thenReturn(Optional.of(disputeCase));

        assertThatThrownBy(
                        () ->
                                service.purge(
                                        "CASE_external",
                                        new AuthenticatedActor(
                                                "reviewer-local",
                                                ActorRole.PLATFORM_REVIEWER)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("only intake-created or simulated imported cases can be deleted");

        verifyNoInteractions(purgeStore);
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoCasePurgeServiceTest.purgesNewAndLegacySimulatedImports(String)」。
    // 具体功能：「DemoCasePurgeServiceTest.purgesNewAndLegacySimulatedImports(String)」：复现“核对完整业务行为（场景方法「purgesNewAndLegacySimulatedImports」）”场景：驱动 「caseRepository.findByIdForUpdate」、「service.purge」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_demo」、「reviewer-local」。
    // 上游调用：「DemoCasePurgeServiceTest.purgesNewAndLegacySimulatedImports(String)」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DemoCasePurgeServiceTest.purgesNewAndLegacySimulatedImports(String)」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DemoCasePurgeServiceTest.purgesNewAndLegacySimulatedImports(String)」守住「案件核心与导入」的可执行规格，尤其防止 「CASE_demo」、「reviewer-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @ParameterizedTest
    @MethodSource("simulatedSources")
    void purgesNewAndLegacySimulatedImports(String sourceSystem) {
        FulfillmentCaseEntity disputeCase =
                simulatedCase(CaseSourceType.EXTERNAL_IMPORT, sourceSystem);
        when(caseRepository.findByIdForUpdate("CASE_demo"))
                .thenReturn(Optional.of(disputeCase));

        service.purge(
                "CASE_demo",
                new AuthenticatedActor(
                        "reviewer-local", ActorRole.PLATFORM_REVIEWER));

        verify(purgeStore)
                .purge("CASE_demo", "reviewer-local", ActorRole.PLATFORM_REVIEWER.name());
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoCasePurgeServiceTest.simulatedCase(CaseSourceType,String)」。
    // 具体功能：「DemoCasePurgeServiceTest.simulatedCase(CaseSourceType,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「simulatedCase」）”组装或读取「disputeCase.getSourceType」、「disputeCase.getSourceSystem」、「mock」、「when」，供本测试类的场景方法复用。
    // 上游调用：「DemoCasePurgeServiceTest.simulatedCase(CaseSourceType,String)」由本测试类中的 「DemoCasePurgeServiceTest.rejectsARegularIntakeCase」、「DemoCasePurgeServiceTest.rejectsARealExternalImport」、「DemoCasePurgeServiceTest.purgesNewAndLegacySimulatedImports」 调用。
    // 下游影响：「DemoCasePurgeServiceTest.simulatedCase(CaseSourceType,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DemoCasePurgeServiceTest.simulatedCase(CaseSourceType,String)」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity simulatedCase(
            CaseSourceType sourceType, String sourceSystem) {
        FulfillmentCaseEntity disputeCase = mock(FulfillmentCaseEntity.class);
        when(disputeCase.getSourceType()).thenReturn(sourceType);
        when(disputeCase.getSourceSystem()).thenReturn(sourceSystem);
        return disputeCase;
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoCasePurgeServiceTest.nonReviewerActors()」。
    // 具体功能：「DemoCasePurgeServiceTest.nonReviewerActors()」：作为测试辅助方法为“核对完整业务行为（场景方法「nonReviewerActors」）”组装或读取「AuthenticatedActor」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「DemoCasePurgeServiceTest.nonReviewerActors()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「DemoCasePurgeServiceTest.nonReviewerActors()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DemoCasePurgeServiceTest.nonReviewerActors()」守住「案件核心与导入」的可执行规格，尤其防止 「user-local」、「merchant-local」、「admin-local」、「system」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static Stream<AuthenticatedActor> nonReviewerActors() {
        return Stream.of(
                new AuthenticatedActor("user-local", ActorRole.USER),
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                new AuthenticatedActor("admin-local", ActorRole.ADMIN),
                new AuthenticatedActor("system", ActorRole.SYSTEM));
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoCasePurgeServiceTest.simulatedSources()」。
    // 具体功能：「DemoCasePurgeServiceTest.simulatedSources()」：作为测试辅助方法为“核对完整业务行为（场景方法「simulatedSources」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
    // 上游调用：「DemoCasePurgeServiceTest.simulatedSources()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「DemoCasePurgeServiceTest.simulatedSources()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DemoCasePurgeServiceTest.simulatedSources()」守住「案件核心与导入」的可执行规格，尤其防止 「TEMPLATE_SIMULATED_OMS」、「LLM_SIMULATED_OMS」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static Stream<String> simulatedSources() {
        return Stream.of("TEMPLATE_SIMULATED_OMS", "LLM_SIMULATED_OMS");
    }
}

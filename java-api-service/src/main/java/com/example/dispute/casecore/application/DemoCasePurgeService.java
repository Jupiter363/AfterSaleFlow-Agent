/*
 * 所属模块：案件核心与导入。
 * 文件职责：编排演示案件案件清理规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「purge」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import com.example.dispute.casecore.domain.CaseSourceType;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【案件核心与导入 / 应用编排层】类型「DemoCasePurgeService」。
// 类型职责：编排演示案件案件清理规则、权限校验与事实读写；本类型显式提供 「DemoCasePurgeService」、「purge」。
// 协作关系：主要由 「DemoCasePurgeController.purge」、「DemoCasePurgeControllerTest.deletesAValidatedSimulatedCaseAndReturnsTheStandardEnvelope」、「DemoCasePurgeServiceTest.purgesNewAndLegacySimulatedImports」、「DemoCasePurgeServiceTest.rejectsARealExternalImport」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class DemoCasePurgeService {

    private static final Set<String> PURGEABLE_SOURCE_SYSTEMS =
            Set.of(
                    SimulatedExternalDisputeTemplateCatalog.SOURCE_SYSTEM,
                    "LLM_SIMULATED_OMS");

    private final FulfillmentCaseRepository caseRepository;
    private final DemoCasePurgeStore purgeStore;

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoCasePurgeService.DemoCasePurgeService(FulfillmentCaseRepository,DemoCasePurgeStore)」。
    // 具体功能：「DemoCasePurgeService.DemoCasePurgeService(FulfillmentCaseRepository,DemoCasePurgeStore)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「purgeStore」(DemoCasePurgeStore) 并保存为「DemoCasePurgeService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「DemoCasePurgeService.DemoCasePurgeService(FulfillmentCaseRepository,DemoCasePurgeStore)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「DemoCasePurgeService.DemoCasePurgeService(FulfillmentCaseRepository,DemoCasePurgeStore)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DemoCasePurgeService.DemoCasePurgeService(FulfillmentCaseRepository,DemoCasePurgeStore)」负责主链路中的“演示案件案件清理服务”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public DemoCasePurgeService(
            FulfillmentCaseRepository caseRepository,
            DemoCasePurgeStore purgeStore) {
        this.caseRepository = caseRepository;
        this.purgeStore = purgeStore;
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoCasePurgeService.purge(String,AuthenticatedActor)」。
    // 具体功能：「DemoCasePurgeService.purge(String,AuthenticatedActor)」：清理演示案件案件清理：先由 Spring 事务代理统一提交数据库变化，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「actor.role」、「disputeCase.getSourceType」、「disputeCase.getSourceSystem」；不满足前置条件时抛出 「ForbiddenException」；处理的关键状态/协议值包括 「case_id」，最终返回「DemoCasePurgeView」。
    // 上游调用：「DemoCasePurgeService.purge(String,AuthenticatedActor)」的上游调用点包括 「DemoCasePurgeController.purge」、「DemoCasePurgeServiceTest.rejectsEveryRoleExceptPlatformReviewer」、「DemoCasePurgeServiceTest.returnsNotFoundWhenCaseDoesNotExist」、「DemoCasePurgeServiceTest.rejectsARegularIntakeCase」。
    // 下游影响：「DemoCasePurgeService.purge(String,AuthenticatedActor)」向下依次触达 「caseRepository.findByIdForUpdate」、「actor.role」、「disputeCase.getSourceType」、「disputeCase.getSourceSystem」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「DemoCasePurgeService.purge(String,AuthenticatedActor)」定义原子提交边界；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public DemoCasePurgeView purge(String caseId, AuthenticatedActor actor) {
        if (actor.role() != ActorRole.PLATFORM_REVIEWER) {
            throw new ForbiddenException(
                    "only the platform reviewer can delete simulated cases");
        }

        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case was not found",
                                                Map.of("case_id", caseId)));

        if (disputeCase.getSourceType() != CaseSourceType.EXTERNAL_IMPORT
                || !PURGEABLE_SOURCE_SYSTEMS.contains(
                        disputeCase.getSourceSystem())) {
            throw new ForbiddenException(
                    "only simulated imported cases can be deleted");
        }

        purgeStore.purge(caseId, actor.actorId(), actor.role().name());
        return new DemoCasePurgeView(caseId, true);
    }
}

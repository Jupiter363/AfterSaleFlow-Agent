/*
 * 所属模块：案件核心与导入。
 * 文件职责：编排争议导入规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「importDispute」、「simulateExternalImport」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Facade for direct and simulated external imports.
 *
 * <p>A fair process-wide gate is acquired before the transactional bean, so
 * waiting never consumes a database transaction.
 */
// 所属模块：【案件核心与导入 / 应用编排层】类型「DisputeImportService」。
// 类型职责：编排争议导入规则、权限校验与事实读写；本类型显式提供 「DisputeImportService」、「importDispute」、「importDispute」、「simulateExternalImport」、「importOne」、「directImportTraceId」。
// 协作关系：主要由 「DemoDisputeSeeder.run」、「DisputeImportSimulationController.simulateImport」、「InternalDisputeImportController.importDispute」、「InternalDisputeImportController.simulateImport」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class DisputeImportService {

    private final ExternalCaseImportTransactionService transactionService;
    private final SingleInstanceImportGate importGate;

    // 所属模块：【案件核心与导入 / 应用编排层】「DisputeImportService.DisputeImportService(ExternalCaseImportTransactionService,SingleInstanceImportGate)」。
    // 具体功能：「DisputeImportService.DisputeImportService(ExternalCaseImportTransactionService,SingleInstanceImportGate)」：通过构造器接收 「transactionService」(ExternalCaseImportTransactionService)、「importGate」(SingleInstanceImportGate) 并保存为「DisputeImportService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「DisputeImportService.DisputeImportService(ExternalCaseImportTransactionService,SingleInstanceImportGate)」的上游创建点包括 「DisputeImportServiceTest.setUp」、「DisputeImportServiceTest.simulatedImportDelegatesToTheTransactionalTemplateBoundary」、「DisputeImportServiceTest.simulatedImportReusesTheOriginalRequestKeyForTransactionalReplay」、「DisputeImportServiceTest.directImportsOfDifferentBusinessKeysCannotEnterTheTransactionBoundaryTogether」。
    // 下游影响：「DisputeImportService.DisputeImportService(ExternalCaseImportTransactionService,SingleInstanceImportGate)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DisputeImportService.DisputeImportService(ExternalCaseImportTransactionService,SingleInstanceImportGate)」负责主链路中的“争议导入服务”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public DisputeImportService(
            ExternalCaseImportTransactionService transactionService,
            SingleInstanceImportGate importGate) {
        this.transactionService = transactionService;
        this.importGate = importGate;
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DisputeImportService.importDispute(ImportDisputeCommand,AuthenticatedActor,String)」。
    // 具体功能：「DisputeImportService.importDispute(ImportDisputeCommand,AuthenticatedActor,String)」：导入争议；实际协作者为 「importOne」、「directImportTraceId」、「directImportRequestId」，最终返回「ImportedDisputeView」。
    // 上游调用：「DisputeImportService.importDispute(ImportDisputeCommand,AuthenticatedActor,String)」的上游调用点包括 「InternalDisputeImportController.importDispute」、「DemoDisputeSeeder.run」、「DemoDisputeSeederTest.seedsSixDeterministicDisputesThroughTheImportServiceWhenEnabled」、「DisputeControllerTest.importsExternalDisputesThroughTheInternalServiceBoundary」。
    // 下游影响：「DisputeImportService.importDispute(ImportDisputeCommand,AuthenticatedActor,String)」向下依次触达 「importOne」、「directImportTraceId」、「directImportRequestId」；计算结果以「ImportedDisputeView」交给调用方。
    // 系统意义：「DisputeImportService.importDispute(ImportDisputeCommand,AuthenticatedActor,String)」负责主链路中的“争议”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    public ImportedDisputeView importDispute(
            ImportDisputeCommand command,
            AuthenticatedActor actor,
            String idempotencyKey) {
        return importOne(
                command,
                actor,
                idempotencyKey,
                directImportTraceId(idempotencyKey),
                directImportRequestId(idempotencyKey));
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DisputeImportService.importDispute(ImportDisputeCommand,AuthenticatedActor,String,String,String)」。
    // 具体功能：「DisputeImportService.importDispute(ImportDisputeCommand,AuthenticatedActor,String,String,String)」：导入争议；实际协作者为 「importOne」，最终返回「ImportedDisputeView」。
    // 上游调用：「DisputeImportService.importDispute(ImportDisputeCommand,AuthenticatedActor,String,String,String)」的上游调用点包括 「InternalDisputeImportController.importDispute」、「DemoDisputeSeeder.run」、「DemoDisputeSeederTest.seedsSixDeterministicDisputesThroughTheImportServiceWhenEnabled」、「DisputeControllerTest.importsExternalDisputesThroughTheInternalServiceBoundary」。
    // 下游影响：「DisputeImportService.importDispute(ImportDisputeCommand,AuthenticatedActor,String,String,String)」向下依次触达 「importOne」；计算结果以「ImportedDisputeView」交给调用方。
    // 系统意义：「DisputeImportService.importDispute(ImportDisputeCommand,AuthenticatedActor,String,String,String)」负责主链路中的“争议”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    public ImportedDisputeView importDispute(
            ImportDisputeCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            String requestId) {
        return importOne(command, actor, idempotencyKey, traceId, requestId);
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DisputeImportService.simulateExternalImport(SimulateExternalImportCommand,AuthenticatedActor,String,String,String)」。
    // 具体功能：「DisputeImportService.simulateExternalImport(SimulateExternalImportCommand,AuthenticatedActor,String,String,String)」：模拟外部导入；实际协作者为 「transactionService.simulateExternalImport」、「actor.role」、「importGate.execute」、「requireText」；不满足前置条件时抛出 「SecurityException」；处理的关键状态/协议值包括 「idempotencyKey」，最终返回「SimulatedImportResultView」。
    // 上游调用：「DisputeImportService.simulateExternalImport(SimulateExternalImportCommand,AuthenticatedActor,String,String,String)」的上游调用点包括 「DisputeImportSimulationController.simulateImport」、「InternalDisputeImportController.simulateImport」、「DisputeControllerTest.simulatesExternalImportThroughTheInternalServiceBoundary」、「DisputeControllerTest.simulatesExternalImportFromThePublicDemoExperience」。
    // 下游影响：「DisputeImportService.simulateExternalImport(SimulateExternalImportCommand,AuthenticatedActor,String,String,String)」向下依次触达 「transactionService.simulateExternalImport」、「actor.role」、「importGate.execute」、「requireText」；计算结果以「SimulatedImportResultView」交给调用方。
    // 系统意义：「DisputeImportService.simulateExternalImport(SimulateExternalImportCommand,AuthenticatedActor,String,String,String)」负责主链路中的“外部导入”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    public SimulatedImportResultView simulateExternalImport(
            SimulateExternalImportCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            String requestId) {
        if (actor.role() != ActorRole.SYSTEM) {
            throw new SecurityException("external dispute simulation requires service identity");
        }
        requireText(idempotencyKey, "idempotencyKey");
        return importGate.execute(
                () ->
                        transactionService.simulateExternalImport(
                                command, actor, idempotencyKey, traceId, requestId));
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DisputeImportService.importOne(ImportDisputeCommand,AuthenticatedActor,String,String,String)」。
    // 具体功能：「DisputeImportService.importOne(ImportDisputeCommand,AuthenticatedActor,String,String,String)」：导入One；实际协作者为 「transactionService.importDispute」、「importGate.execute」，最终返回「ImportedDisputeView」。
    // 上游调用：「DisputeImportService.importOne(ImportDisputeCommand,AuthenticatedActor,String,String,String)」的上游调用点包括 「DisputeImportService.importDispute」。
    // 下游影响：「DisputeImportService.importOne(ImportDisputeCommand,AuthenticatedActor,String,String,String)」向下依次触达 「transactionService.importDispute」、「importGate.execute」；计算结果以「ImportedDisputeView」交给调用方。
    // 系统意义：「DisputeImportService.importOne(ImportDisputeCommand,AuthenticatedActor,String,String,String)」负责主链路中的“One”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private ImportedDisputeView importOne(
            ImportDisputeCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            String requestId) {
        return importGate.execute(
                () ->
                        transactionService.importDispute(
                                command, actor, idempotencyKey, traceId, requestId));
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DisputeImportService.directImportTraceId(String)」。
    // 具体功能：「DisputeImportService.directImportTraceId(String)」：构建direct导入链路标识标识；实际协作者为 「compactImportKey」；处理的关键状态/协议值包括 「direct-import-trace-」，最终返回「String」。
    // 上游调用：「DisputeImportService.directImportTraceId(String)」的上游调用点包括 「DisputeImportService.importDispute」。
    // 下游影响：「DisputeImportService.directImportTraceId(String)」向下依次触达 「compactImportKey」；计算结果以「String」交给调用方。
    // 系统意义：「DisputeImportService.directImportTraceId(String)」负责主链路中的“direct导入链路标识标识”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static String directImportTraceId(String idempotencyKey) {
        return "direct-import-trace-" + compactImportKey(idempotencyKey);
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DisputeImportService.directImportRequestId(String)」。
    // 具体功能：「DisputeImportService.directImportRequestId(String)」：构建direct导入请求标识；实际协作者为 「compactImportKey」；处理的关键状态/协议值包括 「direct-import-request-」，最终返回「String」。
    // 上游调用：「DisputeImportService.directImportRequestId(String)」的上游调用点包括 「DisputeImportService.importDispute」。
    // 下游影响：「DisputeImportService.directImportRequestId(String)」向下依次触达 「compactImportKey」；计算结果以「String」交给调用方。
    // 系统意义：「DisputeImportService.directImportRequestId(String)」负责主链路中的“direct导入请求标识”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static String directImportRequestId(String idempotencyKey) {
        return "direct-import-request-" + compactImportKey(idempotencyKey);
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DisputeImportService.compactImportKey(String)」。
    // 具体功能：「DisputeImportService.compactImportKey(String)」：压缩表示导入键；实际协作者为 「UUID.randomUUID」、「value.replaceAll」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」、「[^A-Za-z0-9_.:-]」，最终返回「String」。
    // 上游调用：「DisputeImportService.compactImportKey(String)」的上游调用点包括 「DisputeImportService.directImportTraceId」、「DisputeImportService.directImportRequestId」。
    // 下游影响：「DisputeImportService.compactImportKey(String)」向下依次触达 「UUID.randomUUID」、「value.replaceAll」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「DisputeImportService.compactImportKey(String)」负责主链路中的“导入键”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static String compactImportKey(String value) {
        String normalized =
                value == null || value.isBlank()
                        ? UUID.randomUUID().toString().replace("-", "")
                        : value.replaceAll("[^A-Za-z0-9_.:-]", "-");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DisputeImportService.requireText(String,String)」。
    // 具体功能：「DisputeImportService.requireText(String,String)」：强制校验文本；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「DisputeImportService.requireText(String,String)」的上游调用点包括 「DisputeImportService.simulateExternalImport」。
    // 下游影响：「DisputeImportService.requireText(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DisputeImportService.requireText(String,String)」在“文本”进入下游前阻断非法状态；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

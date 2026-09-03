/*
 * 所属模块：案件核心与导入。
 * 文件职责：声明模拟导入模板Cursor在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByIdForUpdate」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.infrastructure.persistence.repository;

import com.example.dispute.casecore.infrastructure.persistence.entity.SimulatedImportTemplateCursorEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 所属模块：【案件核心与导入 / 仓储接口层】类型「SimulatedImportTemplateCursorRepository」。
// 类型职责：声明模拟导入模板Cursor在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByIdForUpdate」。
// 协作关系：主要由 「ExternalCaseImportTransactionService.simulateExternalImport」、「SimulatedExternalImportTemplateCycleTest.setUp」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface SimulatedImportTemplateCursorRepository
        extends JpaRepository<SimulatedImportTemplateCursorEntity, String> {

    // 所属模块：【案件核心与导入 / 仓储接口层】「SimulatedImportTemplateCursorRepository.findByIdForUpdate(String)」。
    // 具体功能：「SimulatedImportTemplateCursorRepository.findByIdForUpdate(String)」：声明按标识面向更新访问模拟导入模板Cursor的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<SimulatedImportTemplateCursorEntity>」返回。
    // 上游调用：「SimulatedImportTemplateCursorRepository.findByIdForUpdate(String)」的上游调用点包括 「ExternalCaseImportTransactionService.simulateExternalImport」、「SimulatedExternalImportTemplateCycleTest.setUp」。
    // 下游影响：「SimulatedImportTemplateCursorRepository.findByIdForUpdate(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「SimulatedImportTemplateCursorRepository.findByIdForUpdate(String)」直接影响 PostgreSQL 事实投影；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select cursor from SimulatedImportTemplateCursorEntity cursor"
                    + " where cursor.id = :id")
    Optional<SimulatedImportTemplateCursorEntity> findByIdForUpdate(@Param("id") String id);
}

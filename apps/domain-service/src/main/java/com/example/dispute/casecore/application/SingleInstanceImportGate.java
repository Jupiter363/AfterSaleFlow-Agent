/*
 * 所属模块：案件核心与导入。
 * 文件职责：承载SingleInstance导入门禁在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「execute」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Serializes all import transactions inside one Java process. */
// 所属模块：【案件核心与导入 / 应用编排层】类型「SingleInstanceImportGate」。
// 类型职责：承载SingleInstance导入门禁在当前业务模块中的规则与协作边界；本类型显式提供 「execute」。
// 协作关系：主要由 「DisputeImportService.importOne」、「DisputeImportService.simulateExternalImport」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class SingleInstanceImportGate {

    private final ReentrantLock lock = new ReentrantLock(true);

    // 所属模块：【案件核心与导入 / 应用编排层】「SingleInstanceImportGate.execute(Supplier)」。
    // 具体功能：「SingleInstanceImportGate.execute(Supplier)」：执行T；实际协作者为 「Objects.requireNonNull」、「lock.lock」、「lock.unlock」，最终返回「T」。
    // 上游调用：「SingleInstanceImportGate.execute(Supplier)」的上游调用点包括 「DisputeImportService.simulateExternalImport」、「DisputeImportService.importOne」。
    // 下游影响：「SingleInstanceImportGate.execute(Supplier)」向下依次触达 「Objects.requireNonNull」、「lock.lock」、「lock.unlock」；计算结果以「T」交给调用方。
    // 系统意义：「SingleInstanceImportGate.execute(Supplier)」负责主链路中的“T”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    public <T> T execute(Supplier<T> importAction) {
        Objects.requireNonNull(importAction, "importAction must not be null");
        lock.lock();
        try {
            return importAction.get();
        } finally {
            lock.unlock();
        }
    }
}

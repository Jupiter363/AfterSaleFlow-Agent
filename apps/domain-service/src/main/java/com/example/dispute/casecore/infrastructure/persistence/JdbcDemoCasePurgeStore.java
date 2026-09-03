/*
 * 所属模块：案件核心与导入。
 * 文件职责：承载Jdbc演示案件案件清理Store在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「purge」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.infrastructure.persistence;

import com.example.dispute.casecore.application.DemoCasePurgeStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 所属模块：【案件核心与导入 / 持久化适配层】类型「JdbcDemoCasePurgeStore」。
// 类型职责：承载Jdbc演示案件案件清理Store在当前业务模块中的规则与协作边界；本类型显式提供 「JdbcDemoCasePurgeStore」、「purge」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Repository
public class JdbcDemoCasePurgeStore implements DemoCasePurgeStore {

    private final JdbcTemplate jdbcTemplate;

    // 所属模块：【案件核心与导入 / 持久化适配层】「JdbcDemoCasePurgeStore.JdbcDemoCasePurgeStore(JdbcTemplate)」。
    // 具体功能：「JdbcDemoCasePurgeStore.JdbcDemoCasePurgeStore(JdbcTemplate)」：通过构造器接收 「jdbcTemplate」(JdbcTemplate) 并保存为「JdbcDemoCasePurgeStore」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「JdbcDemoCasePurgeStore.JdbcDemoCasePurgeStore(JdbcTemplate)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「JdbcDemoCasePurgeStore.JdbcDemoCasePurgeStore(JdbcTemplate)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「JdbcDemoCasePurgeStore.JdbcDemoCasePurgeStore(JdbcTemplate)」负责主链路中的“Jdbc演示案件案件清理Store”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public JdbcDemoCasePurgeStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 所属模块：【案件核心与导入 / 持久化适配层】「JdbcDemoCasePurgeStore.purge(String,String,String)」。
    // 具体功能：「JdbcDemoCasePurgeStore.purge(String,String,String)」：清理Jdbc演示案件案件清理Store；实际协作者为 「jdbcTemplate.queryForObject」，最终返回「void」。
    // 上游调用：「JdbcDemoCasePurgeStore.purge(String,String,String)」由使用「JdbcDemoCasePurgeStore」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「JdbcDemoCasePurgeStore.purge(String,String,String)」向下依次触达 「jdbcTemplate.queryForObject」。
    // 系统意义：「JdbcDemoCasePurgeStore.purge(String,String,String)」负责主链路中的“Jdbc演示案件案件清理Store”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    @Override
    public void purge(String caseId, String reviewerId, String reviewerRole) {
        jdbcTemplate.queryForObject(
                "select purge_simulated_dispute_case(?, ?, ?)",
                String.class,
                caseId,
                reviewerId,
                reviewerRole);
    }
}

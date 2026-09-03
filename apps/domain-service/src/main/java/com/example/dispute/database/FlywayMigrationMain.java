/*
 * 所属模块：数据库迁移入口。
 * 文件职责：承载FlywayMigration主入口在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「main」；独立执行 Flyway 迁移并验证 PostgreSQL Schema 可用。
 * 关键边界：迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
 */
package com.example.dispute.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

// 所属模块：【数据库迁移入口 / 核心业务层】类型「FlywayMigrationMain」。
// 类型职责：承载FlywayMigration主入口在当前业务模块中的规则与协作边界；本类型显式提供 「FlywayMigrationMain」、「main」、「requiredEnvironment」、「environmentOrDefault」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public final class FlywayMigrationMain {

    // 所属模块：【数据库迁移入口 / 核心业务层】「FlywayMigrationMain.FlywayMigrationMain()」。
    // 具体功能：「FlywayMigrationMain.FlywayMigrationMain()」：创建「FlywayMigrationMain」实例并保留框架或测试所需的无参构造入口；真正的业务状态由后续工厂方法、JPA 或字段赋值完成。
    // 上游调用：「FlywayMigrationMain.FlywayMigrationMain()」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「FlywayMigrationMain.FlywayMigrationMain()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FlywayMigrationMain.FlywayMigrationMain()」负责主链路中的“FlywayMigration主入口”；迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private FlywayMigrationMain() {}

    // 所属模块：【数据库迁移入口 / 核心业务层】「FlywayMigrationMain.main(String[])」。
    // 具体功能：「FlywayMigrationMain.main(String[])」：启动应用FlywayMigration主入口；实际协作者为 「Flyway.configure」、「flyway.migrate」、「flyway.info」、「requiredEnvironment」；处理的关键状态/协议值包括 「POSTGRES_HOST」、「POSTGRES_PORT」、「5432」、「JAVA_DB_NAME」，最终返回「void」。
    // 上游调用：「FlywayMigrationMain.main(String[])」由使用「FlywayMigrationMain」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FlywayMigrationMain.main(String[])」向下依次触达 「Flyway.configure」、「flyway.migrate」、「flyway.info」、「requiredEnvironment」。
    // 系统意义：「FlywayMigrationMain.main(String[])」负责主链路中的“FlywayMigration主入口”；迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
    public static void main(String[] args) {
        Flyway flyway =
                Flyway.configure()
                        .dataSource(
                                "jdbc:postgresql://%s:%s/%s"
                                        .formatted(
                                                requiredEnvironment("POSTGRES_HOST"),
                                                environmentOrDefault(
                                                        "POSTGRES_PORT", "5432"),
                                                requiredEnvironment("JAVA_DB_NAME")),
                                requiredEnvironment("POSTGRES_USER"),
                                requiredEnvironment("POSTGRES_PASSWORD"))
                        .locations("classpath:db/migration")
                        .load();
        MigrateResult result = flyway.migrate();
        String currentVersion =
                flyway.info().current() == null
                        ? "none"
                        : flyway.info().current().getVersion().getVersion();

        System.out.printf(
                "Flyway migration complete: version=%s, migrations=%d%n",
                currentVersion, result.migrationsExecuted);
    }

    // 所属模块：【数据库迁移入口 / 核心业务层】「FlywayMigrationMain.requiredEnvironment(String)」。
    // 具体功能：「FlywayMigrationMain.requiredEnvironment(String)」：校验Environment；实际协作者为 「System.getenv」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「FlywayMigrationMain.requiredEnvironment(String)」的上游调用点包括 「FlywayMigrationMain.main」。
    // 下游影响：「FlywayMigrationMain.requiredEnvironment(String)」向下依次触达 「System.getenv」；计算结果以「String」交给调用方。
    // 系统意义：「FlywayMigrationMain.requiredEnvironment(String)」在“Environment”进入下游前阻断非法状态；迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

    // 所属模块：【数据库迁移入口 / 核心业务层】「FlywayMigrationMain.environmentOrDefault(String,String)」。
    // 具体功能：「FlywayMigrationMain.environmentOrDefault(String,String)」：构建environment或者默认；实际协作者为 「System.getenv」，最终返回「String」。
    // 上游调用：「FlywayMigrationMain.environmentOrDefault(String,String)」的上游调用点包括 「FlywayMigrationMain.main」。
    // 下游影响：「FlywayMigrationMain.environmentOrDefault(String,String)」向下依次触达 「System.getenv」；计算结果以「String」交给调用方。
    // 系统意义：「FlywayMigrationMain.environmentOrDefault(String,String)」负责主链路中的“environment或者默认”；迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

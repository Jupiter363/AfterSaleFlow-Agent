/*
 * 所属模块：后端公共边界。
 * 文件职责：承载争议应用在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「main」；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// 所属模块：【后端公共边界 / 核心业务层】类型「DisputeApplication」。
// 类型职责：承载争议应用在当前业务模块中的规则与协作边界；本类型显式提供 「main」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class DisputeApplication {

    // 所属模块：【后端公共边界 / 核心业务层】「DisputeApplication.main(String[])」。
    // 具体功能：「DisputeApplication.main(String[])」：启动应用争议应用；实际协作者为 「SpringApplication.run」，最终返回「void」。
    // 上游调用：「DisputeApplication.main(String[])」由使用「DisputeApplication」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「DisputeApplication.main(String[])」向下依次触达 「SpringApplication.run」。
    // 系统意义：「DisputeApplication.main(String[])」负责主链路中的“争议应用”；公共组件不得暗含具体案件裁决规则
    public static void main(String[] args) {
        SpringApplication.run(DisputeApplication.class, args);
    }
}

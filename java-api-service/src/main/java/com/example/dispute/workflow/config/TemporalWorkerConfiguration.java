/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：在 Spring 启动期装配Temporal后台工作器所需 Bean 和基础设施参数。
 * 业务链路：核心入口/契约为 「caseWorkflowWorkerFactory」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.config;

import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.application.EvidenceWindowActivitiesAdapter;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「TemporalWorkerConfiguration」。
// 类型职责：在 Spring 启动期装配Temporal后台工作器所需 Bean 和基础设施参数；本类型显式提供 「caseWorkflowWorkerFactory」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Configuration
@ConditionalOnProperty(
        name = "app.temporal.worker-enabled",
        havingValue = "true")
public class TemporalWorkerConfiguration {

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「TemporalWorkerConfiguration.caseWorkflowWorkerFactory(WorkflowClient,AppProperties,CaseFulfillmentDisputeActivitiesImpl,FinalWorkflowActivitiesAdapter,EvidenceWindowActivitiesAdapter)」。
    // 具体功能：「TemporalWorkerConfiguration.caseWorkflowWorkerFactory(WorkflowClient,AppProperties,CaseFulfillmentDisputeActivitiesImpl,FinalWorkflowActivitiesAdapter,EvidenceWindowActivitiesAdapter)」：构建案件工作流后台工作器工厂；实际协作者为 「WorkerFactory.newInstance」、「factory.newWorker」、「worker.registerWorkflowImplementationTypes」、「worker.registerActivitiesImplementations」，最终返回「WorkerFactory」。
    // 上游调用：「TemporalWorkerConfiguration.caseWorkflowWorkerFactory(WorkflowClient,AppProperties,CaseFulfillmentDisputeActivitiesImpl,FinalWorkflowActivitiesAdapter,EvidenceWindowActivitiesAdapter)」由 Spring ApplicationContext 启动过程调用，配置属性完成绑定后执行本工厂方法。
    // 下游影响：「TemporalWorkerConfiguration.caseWorkflowWorkerFactory(WorkflowClient,AppProperties,CaseFulfillmentDisputeActivitiesImpl,FinalWorkflowActivitiesAdapter,EvidenceWindowActivitiesAdapter)」向下依次触达 「WorkerFactory.newInstance」、「factory.newWorker」、「worker.registerWorkflowImplementationTypes」、「worker.registerActivitiesImplementations」；计算结果以「WorkerFactory」交给调用方。
    // 系统意义：「TemporalWorkerConfiguration.caseWorkflowWorkerFactory(WorkflowClient,AppProperties,CaseFulfillmentDisputeActivitiesImpl,FinalWorkflowActivitiesAdapter,EvidenceWindowActivitiesAdapter)」负责主链路中的“案件工作流后台工作器工厂”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    @Bean(destroyMethod = "shutdown")
    WorkerFactory caseWorkflowWorkerFactory(
            WorkflowClient workflowClient,
            AppProperties properties,
            EvidenceWindowActivitiesAdapter evidenceWindowActivities) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(properties.temporal().taskQueue());
        worker.registerWorkflowImplementationTypes(EvidenceWindowWorkflowImpl.class);
        worker.registerActivitiesImplementations(evidenceWindowActivities);
        factory.start();
        return factory;
    }
}

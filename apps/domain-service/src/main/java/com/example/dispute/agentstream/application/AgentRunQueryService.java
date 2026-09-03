/*
 * 所属模块：Agent 流式运行。
 * 文件职责：编排Agent运行Query规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「get」、「active」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentRunQueryService」。
// 类型职责：编排Agent运行Query规则、权限校验与事实读写；本类型显式提供 「AgentRunQueryService」、「get」、「active」、「view」。
// 协作关系：主要由 「AgentRunController.get」、「CaseAgentRunController.active」、「ReviewCopilotStreamService.active」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class AgentRunQueryService {

    private final AgentRunStreamEventService eventService;
    private final AgentRunRepository runRepository;

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunQueryService.AgentRunQueryService(AgentRunStreamEventService,AgentRunRepository)」。
    // 具体功能：「AgentRunQueryService.AgentRunQueryService(AgentRunStreamEventService,AgentRunRepository)」：通过构造器接收 「eventService」(AgentRunStreamEventService)、「runRepository」(AgentRunRepository) 并保存为「AgentRunQueryService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AgentRunQueryService.AgentRunQueryService(AgentRunStreamEventService,AgentRunRepository)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「AgentRunQueryService.AgentRunQueryService(AgentRunStreamEventService,AgentRunRepository)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunQueryService.AgentRunQueryService(AgentRunStreamEventService,AgentRunRepository)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentRunQueryService(
            AgentRunStreamEventService eventService,
            AgentRunRepository runRepository) {
        this.eventService = eventService;
        this.runRepository = runRepository;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunQueryService.get(String,AuthenticatedActor)」。
    // 具体功能：「AgentRunQueryService.get(String,AuthenticatedActor)」：读取Agent运行：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「eventService.requireVisibleRun」、「view」，最终返回「AgentRunView」。
    // 上游调用：「AgentRunQueryService.get(String,AuthenticatedActor)」的上游调用点包括 「AgentRunController.get」。
    // 下游影响：「AgentRunQueryService.get(String,AuthenticatedActor)」向下依次触达 「eventService.requireVisibleRun」、「view」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentRunQueryService.get(String,AuthenticatedActor)」定义原子提交边界；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public AgentRunView get(String runId, AuthenticatedActor actor) {
        AgentRunEntity run = eventService.requireVisibleRun(runId, actor);
        return view(run);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunQueryService.active(String,String,AuthenticatedActor)」。
    // 具体功能：「AgentRunQueryService.active(String,String,AuthenticatedActor)」：查询活动状态列表：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「findTop20ByCaseIdAndRoomIdAndRunStatusInAndStreamOperationIsNotNullOrderByCreatedAtDesc」、「eventService.requireVisibleRun」、「run.getId」；处理的关键状态/协议值包括 「PENDING」、「RUNNING」，最终返回「List<AgentRunView>」。
    // 上游调用：「AgentRunQueryService.active(String,String,AuthenticatedActor)」的上游调用点包括 「CaseAgentRunController.active」、「ReviewCopilotStreamService.active」。
    // 下游影响：「AgentRunQueryService.active(String,String,AuthenticatedActor)」向下依次触达 「findTop20ByCaseIdAndRoomIdAndRunStatusInAndStreamOperationIsNotNullOrderByCreatedAtDesc」、「eventService.requireVisibleRun」、「run.getId」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentRunQueryService.active(String,String,AuthenticatedActor)」定义原子提交边界；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public List<AgentRunView> active(
            String caseId, String roomId, AuthenticatedActor actor) {
        return runRepository
                .findTop20ByCaseIdAndRoomIdAndRunStatusInAndStreamOperationIsNotNullOrderByCreatedAtDesc(
                        caseId, roomId, List.of("PENDING", "RUNNING"))
                .stream()
                .filter(
                        run -> {
                            try {
                                eventService.requireVisibleRun(run.getId(), actor);
                                return true;
                            } catch (ForbiddenException forbidden) {
                                return false;
                            }
                        })
                .map(this::view)
                .toList();
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunQueryService.view(AgentRunEntity)」。
    // 具体功能：「AgentRunQueryService.view(AgentRunEntity)」：构建视图；实际协作者为 「run.getId」、「run.getCaseId」、「run.getRoomId」、「run.getStreamOperation」，最终返回「AgentRunView」。
    // 上游调用：「AgentRunQueryService.view(AgentRunEntity)」的上游调用点包括 「AgentRunQueryService.get」。
    // 下游影响：「AgentRunQueryService.view(AgentRunEntity)」向下依次触达 「run.getId」、「run.getCaseId」、「run.getRoomId」、「run.getStreamOperation」；计算结果以「AgentRunView」交给调用方。
    // 系统意义：「AgentRunQueryService.view(AgentRunEntity)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private AgentRunView view(AgentRunEntity run) {
        return new AgentRunView(
                run.getId(),
                run.getCaseId(),
                run.getRoomId(),
                run.getStreamOperation(),
                run.getRunStatus(),
                run.getErrorCode(),
                run.getErrorRetryable(),
                "/api/agent-runs/" + run.getId() + "/events",
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getUpdatedAt());
    }
}

/*
 * 所属模块：Agent 流式运行。
 * 文件职责：声明Agent运行流事件在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「findByAgentRunIdAndSequenceNo」、「existsByAgentRunIdAndEventType」、「findMaxSequenceByAgentRunId」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

// 所属模块：【Agent 流式运行 / 持久化适配层】类型「AgentRunStreamEventRepository」。
// 类型职责：声明Agent运行流事件在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「findByAgentRunIdAndSequenceNo」、「existsByAgentRunIdAndEventType」、「findMaxSequenceByAgentRunId」。
// 协作关系：主要由 「AgentRunStreamEventService.append」、「AgentRunStreamEventService.catchUp」、「AgentRunStreamEventService.hasVisibleOutput」、「AgentRunStreamEventService.nextSequence」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface AgentRunStreamEventRepository
        extends JpaRepository<AgentRunStreamEventEntity, String> {

    // 所属模块：【Agent 流式运行 / 持久化适配层】「AgentRunStreamEventRepository.findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(String,long)」。
    // 具体功能：「AgentRunStreamEventRepository.findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(String,long)」：声明按Agent运行标识、序号编号GreaterThan访问Agent运行流事件的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<AgentRunStreamEventEntity>」返回。
    // 上游调用：「AgentRunStreamEventRepository.findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(String,long)」的上游调用点包括 「AgentRunStreamEventService.replay」、「AgentRunStreamEventService.catchUp」、「AgentRunStreamEventServiceTest.replayUsesExclusiveCursorAndPreservesSequenceOrder」、「AgentRunStreamEventServiceTest.administratorCanReadAnActorScopedRun」。
    // 下游影响：「AgentRunStreamEventRepository.findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(String,long)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentRunStreamEventRepository.findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(String,long)」直接影响 PostgreSQL 事实投影；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Query("select event from AgentRunStreamEventEntity event where event.agentRunId = :runId and event.streamProtocol = 'agent_stream.v1' and event.sequenceNo > :sequenceNo order by event.sequenceNo asc")
    List<AgentRunStreamEventEntity>
            findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                    @Param("runId") String agentRunId, @Param("sequenceNo") long sequenceNo);

    @Query("select event from AgentRunStreamEventEntity event where event.agentRunId = :runId and event.streamProtocol = 'agent_stream.v1' and event.sequenceNo > :sequenceNo order by event.sequenceNo asc")
    List<AgentRunStreamEventEntity> findV1ReplayPage(
            @Param("runId") String runId,
            @Param("sequenceNo") long sequenceNo,
            Pageable pageable);

    // 所属模块：【Agent 流式运行 / 持久化适配层】「AgentRunStreamEventRepository.findByAgentRunIdAndSequenceNo(String,long)」。
    // 具体功能：「AgentRunStreamEventRepository.findByAgentRunIdAndSequenceNo(String,long)」：声明按Agent运行标识、序号编号访问Agent运行流事件的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<AgentRunStreamEventEntity>」返回。
    // 上游调用：「AgentRunStreamEventRepository.findByAgentRunIdAndSequenceNo(String,long)」的上游调用点包括 「AgentRunStreamEventService.append」、「AgentRunStreamEventServiceTest.appendIsIdempotentForAnExistingSequence」、「AgentRunStreamEventServiceTest.appendPersistsASequenceOnlyOnce」。
    // 下游影响：「AgentRunStreamEventRepository.findByAgentRunIdAndSequenceNo(String,long)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentRunStreamEventRepository.findByAgentRunIdAndSequenceNo(String,long)」直接影响 PostgreSQL 事实投影；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Query("select event from AgentRunStreamEventEntity event where event.agentRunId = :runId and event.streamProtocol = 'agent_stream.v1' and event.sequenceNo = :sequenceNo")
    Optional<AgentRunStreamEventEntity> findByAgentRunIdAndSequenceNo(
            @Param("runId") String agentRunId, @Param("sequenceNo") long sequenceNo);

    // 所属模块：【Agent 流式运行 / 持久化适配层】「AgentRunStreamEventRepository.existsByAgentRunIdAndEventType(String,String)」。
    // 具体功能：「AgentRunStreamEventRepository.existsByAgentRunIdAndEventType(String,String)」：声明按Agent运行标识、事件类型访问Agent运行流事件的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「boolean」返回。
    // 上游调用：「AgentRunStreamEventRepository.existsByAgentRunIdAndEventType(String,String)」的上游调用点包括 「AgentRunStreamEventService.hasVisibleOutput」。
    // 下游影响：「AgentRunStreamEventRepository.existsByAgentRunIdAndEventType(String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentRunStreamEventRepository.existsByAgentRunIdAndEventType(String,String)」直接影响 PostgreSQL 事实投影；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Query("select (count(event) > 0) from AgentRunStreamEventEntity event where event.agentRunId = :runId and event.streamProtocol = 'agent_stream.v1' and event.eventType = :eventType")
    boolean existsByAgentRunIdAndEventType(
            @Param("runId") String agentRunId, @Param("eventType") String eventType);

    // 所属模块：【Agent 流式运行 / 持久化适配层】「AgentRunStreamEventRepository.findMaxSequenceByAgentRunId(String)」。
    // 具体功能：「AgentRunStreamEventRepository.findMaxSequenceByAgentRunId(String)」：声明按较高风险等级序号按Agent运行标识访问Agent运行流事件的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「long」返回。
    // 上游调用：「AgentRunStreamEventRepository.findMaxSequenceByAgentRunId(String)」的上游调用点包括 「AgentRunStreamEventService.nextSequence」。
    // 下游影响：「AgentRunStreamEventRepository.findMaxSequenceByAgentRunId(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentRunStreamEventRepository.findMaxSequenceByAgentRunId(String)」直接影响 PostgreSQL 事实投影；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Query("select coalesce(max(event.sequenceNo), -1) from AgentRunStreamEventEntity event where event.agentRunId = :runId and event.streamProtocol = 'agent_stream.v1'")
    long findMaxSequenceByAgentRunId(@Param("runId") String runId);

    @Query("select event from AgentRunStreamEventEntity event where event.agentRunId = :runId and event.agentRunAttemptId = :attemptId and event.streamProtocol = 'agent-stream.v2' and event.sequenceNo > :sequenceNo order by event.sequenceNo asc")
    List<AgentRunStreamEventEntity> findV2Replay(
            @Param("runId") String runId,
            @Param("attemptId") String attemptId,
            @Param("sequenceNo") long sequenceNo);

    @Query("select event from AgentRunStreamEventEntity event where event.agentRunId = :runId and event.agentRunAttemptId = :attemptId and event.streamProtocol = 'agent-stream.v2' and event.sequenceNo > :sequenceNo order by event.sequenceNo asc")
    List<AgentRunStreamEventEntity> findV2ReplayPage(
            @Param("runId") String runId,
            @Param("attemptId") String attemptId,
            @Param("sequenceNo") long sequenceNo,
            Pageable pageable);

    @Query("select event from AgentRunStreamEventEntity event where event.agentRunId = :runId and event.agentRunAttemptId = :attemptId and event.streamProtocol = 'agent-stream.v2' and event.sequenceNo = :sequenceNo")
    Optional<AgentRunStreamEventEntity> findV2Event(
            @Param("runId") String runId,
            @Param("attemptId") String attemptId,
            @Param("sequenceNo") long sequenceNo);

    @Query("select coalesce(max(event.sequenceNo), -1) from AgentRunStreamEventEntity event where event.agentRunId = :runId and event.agentRunAttemptId = :attemptId and event.streamProtocol = 'agent-stream.v2'")
    long findMaxV2Sequence(
            @Param("runId") String runId, @Param("attemptId") String attemptId);
}

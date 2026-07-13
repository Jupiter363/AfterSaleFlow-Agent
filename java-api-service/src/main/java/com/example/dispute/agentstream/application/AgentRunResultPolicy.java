/*
 * 所属模块：Agent 流式运行。
 * 文件职责：以确定性规则计算Agent 最终结果 Schema 与公开字段投影，输出可解释且可测试的决策。
 * 业务链路：核心入口/契约为 「validate」、「publicProjection」、「publicUsage」、「totalTokens」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentRunResultPolicy」。
// 类型职责：以确定性规则计算Agent 最终结果 Schema 与公开字段投影，输出可解释且可测试的决策；本类型显式提供 「AgentRunResultPolicy」、「validate」、「publicProjection」、「publicUsage」、「totalTokens」、「requireText」。
// 协作关系：主要由 「AgentRunLifecycleService.complete」、「AgentRunLifecycleService.recordNonTerminalFrame」、「AgentRunLifecycleServiceTest.setUp」、「AgentRunResultPolicyTest.evidenceTurnCannotSmuggleAResponsibilityOrRemedyDecision」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class AgentRunResultPolicy {

    private final ObjectMapper objectMapper;
    private final AgentStreamOperationRegistry operationRegistry;

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunResultPolicy.AgentRunResultPolicy(ObjectMapper,AgentStreamOperationRegistry)」。
    // 具体功能：「AgentRunResultPolicy.AgentRunResultPolicy(ObjectMapper,AgentStreamOperationRegistry)」：通过构造器接收 「objectMapper」(ObjectMapper)、「operationRegistry」(AgentStreamOperationRegistry) 并保存为「AgentRunResultPolicy」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AgentRunResultPolicy.AgentRunResultPolicy(ObjectMapper,AgentStreamOperationRegistry)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「AgentRunLifecycleServiceTest.setUp」 显式创建。
    // 下游影响：「AgentRunResultPolicy.AgentRunResultPolicy(ObjectMapper,AgentStreamOperationRegistry)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunResultPolicy.AgentRunResultPolicy(ObjectMapper,AgentStreamOperationRegistry)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentRunResultPolicy(
            ObjectMapper objectMapper,
            AgentStreamOperationRegistry operationRegistry) {
        this.objectMapper = objectMapper;
        this.operationRegistry = operationRegistry;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunResultPolicy.validate(String,JsonNode)」。
    // 具体功能：「AgentRunResultPolicy.validate(String,JsonNode)」：按 operation 校验 final JSON：房间回合必须包含公开话术、卷轴快照和画布操作，庭审等操作必须满足各自结构；不合格结果不能进入 Finalizer 或数据库终态，最终返回「void」。
    // 上游调用：「AgentRunResultPolicy.validate(String,JsonNode)」的上游调用点包括 「AgentRunLifecycleService.complete」、「AgentRunResultPolicyTest.evidenceTurnCannotSmuggleAResponsibilityOrRemedyDecision」、「AgentRunResultPolicyTest.reviewProjectionExposesAnswerButNotFrozenPacketOrReferences」。
    // 下游影响：「AgentRunResultPolicy.validate(String,JsonNode)」向下依次触达 「result.isObject」、「requireText」、「requireObject」、「requireArray」。
    // 系统意义：「AgentRunResultPolicy.validate(String,JsonNode)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    public void validate(String operation, JsonNode result) {
        if (result == null || !result.isObject()) {
            throw new AgentStreamProtocolException("final result must be an object");
        }
        switch (operation) {
            case "INTAKE_TURN" -> {
                requireText(result, "room_utterance");
                requireObject(result, "scroll_snapshot");
                requireArray(result, "canvas_operations");
            }
            case "EVIDENCE_TURN" -> {
                requireText(result, "room_utterance");
                requireObject(result, "memory_patch");
                requireArray(result, "canvas_operations");
                if (result.path("liability_determined").asBoolean(false)
                        || result.path("remedy_recommended").asBoolean(false)) {
                    throw new AgentStreamProtocolException(
                            "evidence result contains a prohibited decision");
                }
            }
            case "HEARING_ROUND" -> {
                requireText(result, "message_text");
                requireText(result, "speaker_role");
                if (result.path("round_no").asInt(0) < 1) {
                    throw new AgentStreamProtocolException(
                            "hearing result has invalid round_no");
                }
            }
            case "REVIEW" -> requireText(result, "answer");
            default -> {
                // Python owns the complete Pydantic schema.  Java still requires an object and
                // exposes only the server-owned projection below.
            }
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunResultPolicy.publicProjection(String,JsonNode)」。
    // 具体功能：「AgentRunResultPolicy.publicProjection(String,JsonNode)」：从完整模型结果中只复制注册表允许公开的点号字段；EXTERNAL_IMPORT 仅暴露首条模拟案件的标题和描述，其余内部推理、风险与工具建议不会进入 SSE，最终返回「ObjectNode」。
    // 上游调用：「AgentRunResultPolicy.publicProjection(String,JsonNode)」的上游调用点包括 「AgentRunLifecycleService.complete」、「AgentRunResultPolicyTest.hearingProjectionOnlyContainsExplicitlyPublicDraftText」、「AgentRunResultPolicyTest.reviewProjectionExposesAnswerButNotFrozenPacketOrReferences」。
    // 下游影响：「AgentRunResultPolicy.publicProjection(String,JsonNode)」向下依次触达 「operationRegistry.require」、「objectMapper.createObjectNode」、「first.isObject」、「projection.putArray」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「AgentRunResultPolicy.publicProjection(String,JsonNode)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    public ObjectNode publicProjection(String operation, JsonNode result) {
        ObjectNode projection = objectMapper.createObjectNode();
        if ("EXTERNAL_IMPORT".equals(operation)) {
            JsonNode first = result.path("items").path(0);
            if (first.isObject()) {
                ObjectNode item = projection.putArray("items").addObject();
                copyPublicText(first, item, "title");
                copyPublicText(first, item, "description");
            }
            return projection;
        }
        Set<String> publicFields =
                operationRegistry.require(operation).visibleFieldPaths();
        for (String fieldPath : publicFields) {
            JsonNode value = atDottedPath(result, fieldPath);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                putDottedPath(projection, fieldPath, value.asText());
            }
        }
        return projection;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunResultPolicy.publicUsage(JsonNode)」。
    // 具体功能：「AgentRunResultPolicy.publicUsage(JsonNode)」：从不同模型供应商的 token_usage 中筛选非负整数计量字段，丢弃价格、供应商元数据和异常类型，最终返回「ObjectNode」。
    // 上游调用：「AgentRunResultPolicy.publicUsage(JsonNode)」的上游调用点包括 「AgentRunLifecycleService.recordNonTerminalFrame」。
    // 下游影响：「AgentRunResultPolicy.publicUsage(JsonNode)」向下依次触达 「objectMapper.createObjectNode」、「usage.isObject」、「value.canConvertToLong」、「value.asLong」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「AgentRunResultPolicy.publicUsage(JsonNode)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    public ObjectNode publicUsage(JsonNode usage) {
        ObjectNode result = objectMapper.createObjectNode();
        if (usage == null || !usage.isObject()) {
            return result;
        }
        for (String key :
                Set.of(
                        "prompt_tokens",
                        "completion_tokens",
                        "total_tokens",
                        "input_tokens",
                        "output_tokens",
                        "input",
                        "output",
                        "total")) {
            JsonNode value = usage.get(key);
            if (value != null && value.canConvertToLong() && value.asLong() >= 0) {
                result.put(key, value.asLong());
            }
        }
        return result;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunResultPolicy.totalTokens(JsonNode)」。
    // 具体功能：「AgentRunResultPolicy.totalTokens(JsonNode)」：兼容 total、total_tokens 以及 input/output、prompt/completion 两套字段，计算非负总 Token 并钳制到 int 上限，最终返回「int」。
    // 上游调用：「AgentRunResultPolicy.totalTokens(JsonNode)」由使用「AgentRunResultPolicy」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunResultPolicy.totalTokens(JsonNode)」向下依次触达 「Math.max」、「Math.min」、「usage.isObject」、「usage.path("total").asLong」；计算结果以「int」交给调用方。
    // 系统意义：「AgentRunResultPolicy.totalTokens(JsonNode)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    public int totalTokens(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return 0;
        }
        long total = usage.path("total").asLong(-1);
        if (total < 0) {
            total = usage.path("total_tokens").asLong(-1);
        }
        if (total < 0) {
            total =
                    Math.max(
                            usage.path("input").asLong(0)
                                    + usage.path("output").asLong(0),
                            usage.path("prompt_tokens").asLong(0)
                                    + usage.path("completion_tokens").asLong(0));
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, total));
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunResultPolicy.requireText(JsonNode,String)」。
    // 具体功能：「AgentRunResultPolicy.requireText(JsonNode,String)」：强制校验文本；实际协作者为 「result.path(field).asText」；不满足前置条件时抛出 「AgentStreamProtocolException」，最终返回「void」。
    // 上游调用：「AgentRunResultPolicy.requireText(JsonNode,String)」的上游调用点包括 「AgentRunResultPolicy.validate」。
    // 下游影响：「AgentRunResultPolicy.requireText(JsonNode,String)」向下依次触达 「result.path(field).asText」。
    // 系统意义：「AgentRunResultPolicy.requireText(JsonNode,String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static void requireText(JsonNode result, String field) {
        if (result.path(field).asText("").isBlank()) {
            throw new AgentStreamProtocolException(
                    "final result is missing " + field);
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunResultPolicy.requireObject(JsonNode,String)」。
    // 具体功能：「AgentRunResultPolicy.requireObject(JsonNode,String)」：强制校验对象；实际协作者为 「result.path(field).isObject」；不满足前置条件时抛出 「AgentStreamProtocolException」，最终返回「void」。
    // 上游调用：「AgentRunResultPolicy.requireObject(JsonNode,String)」的上游调用点包括 「AgentRunResultPolicy.validate」。
    // 下游影响：「AgentRunResultPolicy.requireObject(JsonNode,String)」向下依次触达 「result.path(field).isObject」。
    // 系统意义：「AgentRunResultPolicy.requireObject(JsonNode,String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static void requireObject(JsonNode result, String field) {
        if (!result.path(field).isObject()) {
            throw new AgentStreamProtocolException(
                    "final result is missing object " + field);
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunResultPolicy.requireArray(JsonNode,String)」。
    // 具体功能：「AgentRunResultPolicy.requireArray(JsonNode,String)」：强制校验数组；实际协作者为 「result.path(field).isArray」；不满足前置条件时抛出 「AgentStreamProtocolException」，最终返回「void」。
    // 上游调用：「AgentRunResultPolicy.requireArray(JsonNode,String)」的上游调用点包括 「AgentRunResultPolicy.validate」。
    // 下游影响：「AgentRunResultPolicy.requireArray(JsonNode,String)」向下依次触达 「result.path(field).isArray」。
    // 系统意义：「AgentRunResultPolicy.requireArray(JsonNode,String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static void requireArray(JsonNode result, String field) {
        if (!result.path(field).isArray()) {
            throw new AgentStreamProtocolException(
                    "final result is missing array " + field);
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunResultPolicy.atDottedPath(JsonNode,String)」。
    // 具体功能：「AgentRunResultPolicy.atDottedPath(JsonNode,String)」：沿点号分隔路径逐层读取 JsonNode；任一中间字段不存在即返回 null，供公开投影跳过未生成字段，最终返回「JsonNode」。
    // 上游调用：「AgentRunResultPolicy.atDottedPath(JsonNode,String)」的上游调用点包括 「AgentRunResultPolicy.publicProjection」。
    // 下游影响：「AgentRunResultPolicy.atDottedPath(JsonNode,String)」向下依次触达 「path.split」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「AgentRunResultPolicy.atDottedPath(JsonNode,String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static JsonNode atDottedPath(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            current = current.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunResultPolicy.putDottedPath(ObjectNode,String,String)」。
    // 具体功能：「AgentRunResultPolicy.putDottedPath(ObjectNode,String,String)」：在公开 ObjectNode 中按点号路径补建中间对象并写入末端文本，保持投影字段与注册表路径一致，最终返回「void」。
    // 上游调用：「AgentRunResultPolicy.putDottedPath(ObjectNode,String,String)」的上游调用点包括 「AgentRunResultPolicy.publicProjection」。
    // 下游影响：「AgentRunResultPolicy.putDottedPath(ObjectNode,String,String)」向下依次触达 「path.split」、「current.putObject」。
    // 系统意义：「AgentRunResultPolicy.putDottedPath(ObjectNode,String,String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static void putDottedPath(ObjectNode root, String path, String value) {
        String[] segments = path.split("\\.");
        ObjectNode current = root;
        for (int index = 0; index < segments.length - 1; index++) {
            JsonNode existing = current.get(segments[index]);
            if (existing instanceof ObjectNode object) {
                current = object;
            } else {
                current = current.putObject(segments[index]);
            }
        }
        current.put(segments[segments.length - 1], value);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunResultPolicy.copyPublicText(JsonNode,ObjectNode,String)」。
    // 具体功能：「AgentRunResultPolicy.copyPublicText(JsonNode,ObjectNode,String)」：仅复制存在、文本类型且非空白的公开字段，避免 null、对象或空串污染对外结果，最终返回「void」。
    // 上游调用：「AgentRunResultPolicy.copyPublicText(JsonNode,ObjectNode,String)」的上游调用点包括 「AgentRunResultPolicy.publicProjection」。
    // 下游影响：「AgentRunResultPolicy.copyPublicText(JsonNode,ObjectNode,String)」向下依次触达 「value.isTextual」、「value.asText」。
    // 系统意义：「AgentRunResultPolicy.copyPublicText(JsonNode,ObjectNode,String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static void copyPublicText(
            JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            target.put(field, value.asText());
        }
    }
}

/*
 * 所属模块：平台人工终审。
 * 文件职责：编排审核Copilot流规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「query」、「active」；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review.application;

import com.example.dispute.agentstream.application.AgentRunAcceptedView;
import com.example.dispute.agentstream.application.AgentRunCoordinator;
import com.example.dispute.agentstream.application.AgentRunQueryService;
import com.example.dispute.agentstream.application.AgentRunStartCommand;
import com.example.dispute.agentstream.application.AgentRunView;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.PlatformReviewerAuthorization;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Starts reviewer-only copilot runs from the immutable review packet.
 *
 * <p>The browser can only submit a question. Java owns authorization, packet selection,
 * idempotency and audience isolation; Python receives the server-built frozen context and can
 * never approve or execute a decision through this path.
 */
// 所属模块：【平台人工终审 / 应用编排层】类型「ReviewCopilotStreamService」。
// 类型职责：编排审核Copilot流规则、权限校验与事实读写；本类型显式提供 「ReviewCopilotStreamService」、「query」、「active」、「request」、「frozenPacket」、「addIdentifier」。
// 协作关系：主要由 「ReviewController.activeCopilotRuns」、「ReviewController.queryCopilot」 使用。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class ReviewCopilotStreamService {

    private static final String OPERATION = "REVIEW";

    private final ReviewApplicationService reviewService;
    private final AgentRunCoordinator runCoordinator;
    private final AgentRunQueryService runQueryService;
    private final ObjectMapper objectMapper;

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewCopilotStreamService.ReviewCopilotStreamService(ReviewApplicationService,AgentRunCoordinator,AgentRunQueryService,ObjectMapper)」。
    // 具体功能：「ReviewCopilotStreamService.ReviewCopilotStreamService(ReviewApplicationService,AgentRunCoordinator,AgentRunQueryService,ObjectMapper)」：通过构造器接收 「reviewService」(ReviewApplicationService)、「runCoordinator」(AgentRunCoordinator)、「runQueryService」(AgentRunQueryService)、「objectMapper」(ObjectMapper) 并保存为「ReviewCopilotStreamService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「ReviewCopilotStreamService.ReviewCopilotStreamService(ReviewApplicationService,AgentRunCoordinator,AgentRunQueryService,ObjectMapper)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「ReviewCopilotStreamService.ReviewCopilotStreamService(ReviewApplicationService,AgentRunCoordinator,AgentRunQueryService,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewCopilotStreamService.ReviewCopilotStreamService(ReviewApplicationService,AgentRunCoordinator,AgentRunQueryService,ObjectMapper)」负责主链路中的“审核Copilot流服务”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public ReviewCopilotStreamService(
            ReviewApplicationService reviewService,
            AgentRunCoordinator runCoordinator,
            AgentRunQueryService runQueryService,
            ObjectMapper objectMapper) {
        this.reviewService = reviewService;
        this.runCoordinator = runCoordinator;
        this.runQueryService = runQueryService;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewCopilotStreamService.query(String,String,String,String,String,AuthenticatedActor)」。
    // 具体功能：「ReviewCopilotStreamService.query(String,String,String,String,String,AuthenticatedActor)」：查询Agent运行受理响应；实际协作者为 「reviewService.packet」、「runCoordinator.start」、「PlatformReviewerAuthorization.requireDecisionAccess」、「packet.caseId」；处理的关键状态/协议值包括 「PLATFORM_REVIEWER」，最终返回「AgentRunAcceptedView」。
    // 上游调用：「ReviewCopilotStreamService.query(String,String,String,String,String,AuthenticatedActor)」的上游调用点包括 「ReviewController.queryCopilot」。
    // 下游影响：「ReviewCopilotStreamService.query(String,String,String,String,String,AuthenticatedActor)」向下依次触达 「reviewService.packet」、「runCoordinator.start」、「PlatformReviewerAuthorization.requireDecisionAccess」、「packet.caseId」；计算结果以「AgentRunAcceptedView」交给调用方。
    // 系统意义：「ReviewCopilotStreamService.query(String,String,String,String,String,AuthenticatedActor)」负责主链路中的“Agent运行受理响应”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    public AgentRunAcceptedView query(
            String taskId,
            String question,
            String idempotencyKey,
            String traceId,
            String requestId,
            AuthenticatedActor actor) {
        PlatformReviewerAuthorization.requireDecisionAccess(actor);
        ReviewPacketView packet = reviewService.packet(taskId, actor);
        ObjectNode request = request(taskId, question, packet);
        return runCoordinator.start(
                new AgentRunStartCommand(
                        packet.caseId(),
                        taskId,
                        OPERATION,
                        request,
                        List.of("PLATFORM_REVIEWER"),
                        List.of(actor.actorId()),
                        idempotencyKey,
                        traceId,
                        requestId,
                        actor.actorId()));
    }

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewCopilotStreamService.active(String,AuthenticatedActor)」。
    // 具体功能：「ReviewCopilotStreamService.active(String,AuthenticatedActor)」：查询活动状态列表；实际协作者为 「reviewService.packet」、「runQueryService.active」、「PlatformReviewerAuthorization.requireDecisionAccess」、「packet.caseId」，最终返回「List<AgentRunView>」。
    // 上游调用：「ReviewCopilotStreamService.active(String,AuthenticatedActor)」的上游调用点包括 「ReviewController.activeCopilotRuns」。
    // 下游影响：「ReviewCopilotStreamService.active(String,AuthenticatedActor)」向下依次触达 「reviewService.packet」、「runQueryService.active」、「PlatformReviewerAuthorization.requireDecisionAccess」、「packet.caseId」；计算结果以「List<AgentRunView>」交给调用方。
    // 系统意义：「ReviewCopilotStreamService.active(String,AuthenticatedActor)」负责主链路中的“列表”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    public List<AgentRunView> active(String taskId, AuthenticatedActor actor) {
        PlatformReviewerAuthorization.requireDecisionAccess(actor);
        ReviewPacketView packet = reviewService.packet(taskId, actor);
        return runQueryService.active(packet.caseId(), taskId, actor).stream()
                .filter(run -> OPERATION.equals(run.operation()))
                .toList();
    }

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewCopilotStreamService.request(String,String,ReviewPacketView)」。
    // 具体功能：「ReviewCopilotStreamService.request(String,String,ReviewPacketView)」：构建请求；实际协作者为 「objectMapper.createObjectNode」、「packet.caseId」、「packet.packetVersion」、「request.putArray」；处理的关键状态/协议值包括 「review_id」、「case_id」、「review_packet_version」、「reviewer_role」，最终返回「ObjectNode」。
    // 上游调用：「ReviewCopilotStreamService.request(String,String,ReviewPacketView)」的上游调用点包括 「ReviewCopilotStreamService.query」。
    // 下游影响：「ReviewCopilotStreamService.request(String,String,ReviewPacketView)」向下依次触达 「objectMapper.createObjectNode」、「packet.caseId」、「packet.packetVersion」、「request.putArray」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「ReviewCopilotStreamService.request(String,String,ReviewPacketView)」负责主链路中的“请求”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private ObjectNode request(
            String taskId, String question, ReviewPacketView packet) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("review_id", taskId);
        request.put("case_id", packet.caseId());
        request.put("review_packet_version", packet.packetVersion());
        request.put("reviewer_role", "PLATFORM_REVIEWER");
        request.put("question", question.trim());

        ArrayNode factRefs = request.putArray("available_fact_refs");
        Set<String> facts = new LinkedHashSet<>();
        collectIdentifiers(
                packet.claims(),
                Set.of("fact_id", "fact_ids", "claim_id", "claim_ids"),
                facts);
        collectIdentifiers(
                packet.evidenceMatrix(),
                Set.of(
                        "fact_id",
                        "fact_ids",
                        "claim_id",
                        "claim_ids",
                        "evidence_id",
                        "evidence_ids",
                        "supporting",
                        "supporting_evidence_ids",
                        "referenced_evidence_ids"),
                facts);
        facts.forEach(value -> addIdentifier(factRefs, value));

        ArrayNode ruleRefs = request.putArray("available_rule_refs");
        addIdentifier(ruleRefs, packet.rulesetVersion());
        Set<String> rules = new LinkedHashSet<>();
        collectIdentifiers(
                packet.draft(),
                Set.of("rule_id", "rule_ids", "rule_code", "rule_codes", "rule_version"),
                rules);
        rules.forEach(value -> addIdentifier(ruleRefs, value));

        ArrayNode draftRefs = request.putArray("available_draft_refs");
        addIdentifier(
                draftRefs,
                packet.draft() == null ? null : packet.draft().path("id").asText(null));

        request.set(
                "available_deliberation_refs", objectMapper.createArrayNode());
        request.set("frozen_packet", frozenPacket(packet));
        return request;
    }

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewCopilotStreamService.frozenPacket(ReviewPacketView)」。
    // 具体功能：「ReviewCopilotStreamService.frozenPacket(ReviewPacketView)」：构建冻结审核包；实际协作者为 「objectMapper.valueToTree」、「value.isObject」；不满足前置条件时抛出 「IllegalStateException」，最终返回「JsonNode」。
    // 上游调用：「ReviewCopilotStreamService.frozenPacket(ReviewPacketView)」的上游调用点包括 「ReviewCopilotStreamService.request」。
    // 下游影响：「ReviewCopilotStreamService.frozenPacket(ReviewPacketView)」向下依次触达 「objectMapper.valueToTree」、「value.isObject」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「ReviewCopilotStreamService.frozenPacket(ReviewPacketView)」负责主链路中的“冻结审核包”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private JsonNode frozenPacket(ReviewPacketView packet) {
        JsonNode value = objectMapper.valueToTree(packet);
        if (!value.isObject()) {
            throw new IllegalStateException("review packet must serialize as an object");
        }
        return value;
    }

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewCopilotStreamService.addIdentifier(ArrayNode,String)」。
    // 具体功能：「ReviewCopilotStreamService.addIdentifier(ArrayNode,String)」：添加标识，最终返回「void」。
    // 上游调用：「ReviewCopilotStreamService.addIdentifier(ArrayNode,String)」的上游调用点包括 「ReviewCopilotStreamService.request」。
    // 下游影响：「ReviewCopilotStreamService.addIdentifier(ArrayNode,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewCopilotStreamService.addIdentifier(ArrayNode,String)」负责主链路中的“标识”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private static void addIdentifier(ArrayNode target, String value) {
        if (value != null
                && !value.isBlank()
                && value.length() >= 3
                && value.length() <= 128) {
            target.add(value);
        }
    }

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewCopilotStreamService.collectIdentifiers(JsonNode,Set,Set)」。
    // 具体功能：「ReviewCopilotStreamService.collectIdentifiers(JsonNode,Set,Set)」：提供「collectIdentifiers」的便捷重载：接收 「node」(JsonNode)、「fieldNames」(Set)、「target」(Set)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「ReviewCopilotStreamService.collectIdentifiers(JsonNode,Set,Set)」的上游调用点包括 「ReviewCopilotStreamService.request」、「ReviewCopilotStreamService.collectIdentifiers」。
    // 下游影响：「ReviewCopilotStreamService.collectIdentifiers(JsonNode,Set,Set)」向下依次触达 「node.isNull」、「node.isMissingNode」、「node.isObject」、「node.fieldNames」。
    // 系统意义：「ReviewCopilotStreamService.collectIdentifiers(JsonNode,Set,Set)」负责主链路中的“collectIdentifiers”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private static void collectIdentifiers(
            JsonNode node, Set<String> fieldNames, Set<String> target) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            node.fieldNames()
                    .forEachRemaining(
                            fieldName -> {
                                JsonNode value = node.get(fieldName);
                                String field = fieldName.toLowerCase(Locale.ROOT);
                                if (fieldNames.contains(field)) {
                                    collectIdentifierValues(value, target);
                                }
                                collectIdentifiers(value, fieldNames, target);
                            });
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectIdentifiers(item, fieldNames, target));
        }
    }

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewCopilotStreamService.collectIdentifierValues(JsonNode,Set)」。
    // 具体功能：「ReviewCopilotStreamService.collectIdentifierValues(JsonNode,Set)」：提供「collectIdentifierValues」的便捷重载：接收 「node」(JsonNode)、「target」(Set)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「ReviewCopilotStreamService.collectIdentifierValues(JsonNode,Set)」的上游调用点包括 「ReviewCopilotStreamService.collectIdentifiers」、「ReviewCopilotStreamService.collectIdentifierValues」。
    // 下游影响：「ReviewCopilotStreamService.collectIdentifierValues(JsonNode,Set)」向下依次触达 「node.isNull」、「node.isTextual」、「node.asText」、「node.isArray」。
    // 系统意义：「ReviewCopilotStreamService.collectIdentifierValues(JsonNode,Set)」负责主链路中的“collect标识Values”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private static void collectIdentifierValues(JsonNode node, Set<String> target) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            String value = node.asText().trim();
            if (value.length() >= 3 && value.length() <= 128) target.add(value);
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectIdentifierValues(item, target));
        }
    }
}

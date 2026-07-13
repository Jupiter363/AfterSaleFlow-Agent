/*
 * 所属模块：平台人工终审。
 * 文件职责：承载动作快照哈希器在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「hash」；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

// 所属模块：【平台人工终审 / 领域模型层】类型「ActionSnapshotHasher」。
// 类型职责：承载动作快照哈希器在当前业务模块中的规则与协作边界；本类型显式提供 「ActionSnapshotHasher」、「hash」、「canonicalize」。
// 协作关系：主要由 「ReviewApplicationService.actionHash」、「ToolExecutorService.loadApprovedExecution」、「ActionSnapshotHasherTest.hashIsStableWhenNestedObjectFieldOrderChanges」、「PostReviewOrchestrationServiceIntegrationTest.seed」 使用。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public final class ActionSnapshotHasher {

    // 所属模块：【平台人工终审 / 领域模型层】「ActionSnapshotHasher.ActionSnapshotHasher()」。
    // 具体功能：「ActionSnapshotHasher.ActionSnapshotHasher()」：创建「ActionSnapshotHasher」实例并保留框架或测试所需的无参构造入口；真正的业务状态由后续工厂方法、JPA 或字段赋值完成。
    // 上游调用：「ActionSnapshotHasher.ActionSnapshotHasher()」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「ActionSnapshotHasher.ActionSnapshotHasher()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ActionSnapshotHasher.ActionSnapshotHasher()」负责主链路中的“动作快照哈希器”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private ActionSnapshotHasher() {}

    // 所属模块：【平台人工终审 / 领域模型层】「ActionSnapshotHasher.hash(ObjectMapper,JsonNode)」。
    // 具体功能：「ActionSnapshotHasher.hash(ObjectMapper,JsonNode)」：计算哈希字符串：先计算稳定哈希以绑定审批快照；实际协作者为 「MessageDigest.getInstance」、「objectMapper.createObjectNode」、「objectMapper.writeValueAsBytes」、「canonicalize」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「id」、「version」、「actions」、「preconditions」，最终返回「String」。
    // 上游调用：「ActionSnapshotHasher.hash(ObjectMapper,JsonNode)」的上游调用点包括 「ToolExecutorService.loadApprovedExecution」、「ReviewApplicationService.actionHash」、「ToolExecutorServiceIntegrationTest.seed」、「ActionSnapshotHasherTest.hashIsStableWhenNestedObjectFieldOrderChanges」。
    // 下游影响：「ActionSnapshotHasher.hash(ObjectMapper,JsonNode)」向下依次触达 「MessageDigest.getInstance」、「objectMapper.createObjectNode」、「objectMapper.writeValueAsBytes」、「canonicalize」；计算结果以「String」交给调用方。
    // 系统意义：「ActionSnapshotHasher.hash(ObjectMapper,JsonNode)」负责主链路中的“字符串”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    public static String hash(ObjectMapper objectMapper, JsonNode plan) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("id", plan.path("id").asText());
        canonical.put("version", plan.path("version").asInt());
        canonical.set("actions", canonicalize(objectMapper, plan.path("actions")));
        canonical.set("preconditions", canonicalize(objectMapper, plan.path("preconditions")));
        canonical.set("notifications", canonicalize(objectMapper, plan.path("notifications")));
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "cannot hash approved action snapshot", exception);
        }
    }

    // 所属模块：【平台人工终审 / 领域模型层】「ActionSnapshotHasher.canonicalize(ObjectMapper,JsonNode)」。
    // 具体功能：「ActionSnapshotHasher.canonicalize(ObjectMapper,JsonNode)」：提供「canonicalize」的便捷重载：接收 「objectMapper」(ObjectMapper)、「node」(JsonNode)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「ActionSnapshotHasher.canonicalize(ObjectMapper,JsonNode)」的上游调用点包括 「ActionSnapshotHasher.hash」、「ActionSnapshotHasher.canonicalize」。
    // 下游影响：「ActionSnapshotHasher.canonicalize(ObjectMapper,JsonNode)」向下依次触达 「node.isMissingNode」、「node.isNull」、「objectMapper.nullNode」、「node.isObject」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「ActionSnapshotHasher.canonicalize(ObjectMapper,JsonNode)」负责主链路中的“canonicalize”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private static JsonNode canonicalize(ObjectMapper objectMapper, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return objectMapper.nullNode();
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream()
                    .sorted()
                    .forEach(name -> result.set(name, canonicalize(objectMapper, node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(item -> result.add(canonicalize(objectMapper, item)));
            return result;
        }
        return node.deepCopy();
    }
}

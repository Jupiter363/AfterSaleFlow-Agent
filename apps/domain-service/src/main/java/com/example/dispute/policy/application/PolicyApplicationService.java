/*
 * 所属模块：规则检索。
 * 文件职责：编排应用规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「findActive」；读取适用政策规则并向路由、庭审和审核阶段提供可引用依据。
 * 关键边界：规则引用需要版本化，不能用模型生成文本替代正式规则事实
 */
package com.example.dispute.policy.application;

import com.example.dispute.infrastructure.persistence.entity.PolicyRuleEntity;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【规则检索 / 应用编排层】类型「PolicyApplicationService」。
// 类型职责：编排应用规则、权限校验与事实读写；本类型显式提供 「PolicyApplicationService」、「findActive」、「toView」、「readMap」。
// 协作关系：主要由 「PolicyController.findActive」 使用。
// 边界意义：规则引用需要版本化，不能用模型生成文本替代正式规则事实
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class PolicyApplicationService {

    private final PolicyRuleRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // 所属模块：【规则检索 / 应用编排层】「PolicyApplicationService.PolicyApplicationService(PolicyRuleRepository,ObjectMapper,Clock)」。
    // 具体功能：「PolicyApplicationService.PolicyApplicationService(PolicyRuleRepository,ObjectMapper,Clock)」：通过构造器接收 「repository」(PolicyRuleRepository)、「objectMapper」(ObjectMapper)、「clock」(Clock) 并保存为「PolicyApplicationService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「PolicyApplicationService.PolicyApplicationService(PolicyRuleRepository,ObjectMapper,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「PolicyApplicationService.PolicyApplicationService(PolicyRuleRepository,ObjectMapper,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PolicyApplicationService.PolicyApplicationService(PolicyRuleRepository,ObjectMapper,Clock)」负责主链路中的“政策规则应用服务”；规则引用需要版本化，不能用模型生成文本替代正式规则事实
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public PolicyApplicationService(
            PolicyRuleRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // 所属模块：【规则检索 / 应用编排层】「PolicyApplicationService.findActive(String)」。
    // 具体功能：「PolicyApplicationService.findActive(String)」：查找当前：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「repository.findActive」、「scope.strip」，最终返回「List<PolicyRuleView>」。
    // 上游调用：「PolicyApplicationService.findActive(String)」的上游调用点包括 「PolicyController.findActive」。
    // 下游影响：「PolicyApplicationService.findActive(String)」向下依次触达 「repository.findActive」、「scope.strip」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「PolicyApplicationService.findActive(String)」定义原子提交边界；规则引用需要版本化，不能用模型生成文本替代正式规则事实
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public List<PolicyRuleView> findActive(String scope) {
        String normalizedScope =
                scope == null || scope.isBlank() ? null : scope.strip().toUpperCase();
        return repository.findActive(normalizedScope, OffsetDateTime.now(clock)).stream()
                .map(this::toView)
                .toList();
    }

    // 所属模块：【规则检索 / 应用编排层】「PolicyApplicationService.toView(PolicyRuleEntity)」。
    // 具体功能：「PolicyApplicationService.toView(PolicyRuleEntity)」：转换视图；实际协作者为 「entity.getId」、「entity.getRuleCode」、「entity.getRuleVersion」、「entity.getRuleName」，最终返回「PolicyRuleView」。
    // 上游调用：「PolicyApplicationService.toView(PolicyRuleEntity)」只由「PolicyApplicationService」内部流程使用，负责封装“视图”这一步校验、映射或状态转换。
    // 下游影响：「PolicyApplicationService.toView(PolicyRuleEntity)」向下依次触达 「entity.getId」、「entity.getRuleCode」、「entity.getRuleVersion」、「entity.getRuleName」；计算结果以「PolicyRuleView」交给调用方。
    // 系统意义：「PolicyApplicationService.toView(PolicyRuleEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；规则引用需要版本化，不能用模型生成文本替代正式规则事实
    private PolicyRuleView toView(PolicyRuleEntity entity) {
        return new PolicyRuleView(
                entity.getId(),
                entity.getRuleCode(),
                entity.getRuleVersion(),
                entity.getRuleName(),
                entity.getRuleScope(),
                entity.getRuleStatus(),
                entity.getEffectiveFrom(),
                entity.getEffectiveTo(),
                entity.getPriority(),
                readMap(entity.getConditionJson()),
                readMap(entity.getOutcomeJson()),
                readMap(entity.getSourceDocumentJson()));
    }

    // 所属模块：【规则检索 / 应用编排层】「PolicyApplicationService.readMap(String)」。
    // 具体功能：「PolicyApplicationService.readMap(String)」：读取映射；实际协作者为 「objectMapper.readValue」；不满足前置条件时抛出 「IllegalStateException」，最终返回「Map<String, Object>」。
    // 上游调用：「PolicyApplicationService.readMap(String)」的上游调用点包括 「PolicyApplicationService.toView」。
    // 下游影响：「PolicyApplicationService.readMap(String)」向下依次触达 「objectMapper.readValue」；计算结果以「Map<String, Object>」交给调用方。
    // 系统意义：「PolicyApplicationService.readMap(String)」统一“映射”的跨层表示，避免不同入口产生不兼容字段；规则引用需要版本化，不能用模型生成文本替代正式规则事实
    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted policy JSON", exception);
        }
    }
}

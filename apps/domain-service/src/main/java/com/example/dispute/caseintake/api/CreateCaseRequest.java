/*
 * 所属模块：案件受理兼容链路。
 * 文件职责：定义Create案件跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「toCommand」；承接旧版创建案件接口并调用接待 Agent 形成初步分析。
 * 关键边界：接待分析只是非最终建议，不能越权决定赔付或执行动作
 */
package com.example.dispute.caseintake.api;

import com.example.dispute.caseintake.application.CreateCaseCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

// 所属模块：【案件受理兼容链路 / HTTP 接口层】类型「CreateCaseRequest」。
// 类型职责：定义Create案件跨层传递时使用的不可变数据契约；本类型显式提供 「CreateCaseRequest」、「toCommand」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record CreateCaseRequest(
        @Size(max = 64) String orderId,
        @Size(max = 64) String afterSaleId,
        @NotBlank @Size(max = 128) String userId,
        @NotBlank @Size(max = 128) String merchantId,
        @NotBlank @Size(max = 4000) String description,
        @Size(max = 50) List<@Size(max = 128) String> attachmentIds,
        @NotBlank @Size(max = 32) String channel) {

    // 所属模块：【案件受理兼容链路 / HTTP 接口层】「CreateCaseRequest.CreateCaseRequest(String,String,String,String,String,List,String)」。
    // 具体功能：「CreateCaseRequest.CreateCaseRequest(String,String,String,String,String,List,String)」：在不可变「CreateCaseRequest」写入组件前校验 「orderId」(String)、「afterSaleId」(String)、「userId」(String)、「merchantId」(String)、「description」(String)、「attachmentIds」(List)、「channel」(String)，并统一规范 record 组件值。
    // 上游调用：「CreateCaseRequest.CreateCaseRequest(String,String,String,String,String,List,String)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「CreateCaseRequest.CreateCaseRequest(String,String,String,String,String,List,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CreateCaseRequest.CreateCaseRequest(String,String,String,String,String,List,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public CreateCaseRequest {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }

    // 所属模块：【案件受理兼容链路 / HTTP 接口层】「CreateCaseRequest.toCommand()」。
    // 具体功能：「CreateCaseRequest.toCommand()」：转换命令，最终返回「CreateCaseCommand」。
    // 上游调用：「CreateCaseRequest.toCommand()」由使用「CreateCaseRequest」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CreateCaseRequest.toCommand()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「CreateCaseCommand」交给调用方。
    // 系统意义：「CreateCaseRequest.toCommand()」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    CreateCaseCommand toCommand() {
        return new CreateCaseCommand(
                orderId,
                afterSaleId,
                null,
                userId,
                merchantId,
                description,
                attachmentIds,
                channel);
    }
}

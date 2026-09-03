/*
 * 所属模块：后端公共边界。
 * 文件职责：表达案件结案失败语义，使上层能够区分业务拒绝、协议错误和可重试故障。
 * 业务链路：该文件主要提供类型或包级契约；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.exception;

import com.example.dispute.common.api.ErrorCode;
import java.util.Map;

// 所属模块：【后端公共边界 / 核心业务层】类型「CaseClosureException」。
// 类型职责：表达案件结案失败语义，使上层能够区分业务拒绝、协议错误和可重试故障；本类型显式提供 「CaseClosureException」。
// 协作关系：主要由 「CaseClosureService.closureDenied」 使用。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public final class CaseClosureException extends BusinessException {

    // 所属模块：【后端公共边界 / 核心业务层】「CaseClosureException.CaseClosureException(String,Map)」。
    // 具体功能：「CaseClosureException.CaseClosureException(String,Map)」：把 「message」(String)、「details」(Map) 交给父异常保存错误链；构造过程不执行日志、重试或业务补偿。
    // 上游调用：「CaseClosureException.CaseClosureException(String,Map)」的上游创建点包括 「CaseClosureService.closureDenied」。
    // 下游影响：「CaseClosureException.CaseClosureException(String,Map)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseClosureException.CaseClosureException(String,Map)」负责主链路中的“案件结案异常”；公共组件不得暗含具体案件裁决规则
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public CaseClosureException(String message, Map<String, Object> details) {
        super(ErrorCode.CASE_STATUS_INVALID, message, details);
    }
}

/*
 * 所属模块：后端公共边界。
 * 文件职责：表达超时失败语义，使上层能够区分业务拒绝、协议错误和可重试故障。
 * 业务链路：该文件主要提供类型或包级契约；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.exception;

import com.example.dispute.common.api.ErrorCode;
import java.util.Map;

// 所属模块：【后端公共边界 / 核心业务层】类型「TimeoutException」。
// 类型职责：表达超时失败语义，使上层能够区分业务拒绝、协议错误和可重试故障；本类型显式提供 「TimeoutException」。
// 协作关系：主要由 「ExceptionHierarchyTest.requiredExceptionTypesShareTheBusinessExceptionContract」 使用。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public final class TimeoutException extends BusinessException {

    // 所属模块：【后端公共边界 / 核心业务层】「TimeoutException.TimeoutException(String)」。
    // 具体功能：「TimeoutException.TimeoutException(String)」：把 「message」(String) 交给父异常保存错误链；构造过程不执行日志、重试或业务补偿。
    // 上游调用：「TimeoutException.TimeoutException(String)」的上游创建点包括 「ExceptionHierarchyTest.requiredExceptionTypesShareTheBusinessExceptionContract」。
    // 下游影响：「TimeoutException.TimeoutException(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「TimeoutException.TimeoutException(String)」负责主链路中的“超时异常”；公共组件不得暗含具体案件裁决规则
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public TimeoutException(String message) {
        super(ErrorCode.EXTERNAL_SERVICE_TIMEOUT, message, Map.of());
    }
}

/*
 * 所属模块：案件核心与导入。
 * 文件职责：验证Create争议，覆盖 「validatesStructuredClaimResolutionSeedWithoutTreatingItAsAnExecutionAction」、「acceptsAllowedStructuredClaimResolutionSeed」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.application.IntakeLobbySeed;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// 所属模块：【案件核心与导入 / HTTP 接口层】类型「CreateDisputeRequestTest」。
// 类型职责：集中验证Create争议的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUpValidator」、「closeValidator」、「validatesStructuredClaimResolutionSeedWithoutTreatingItAsAnExecutionAction」、「acceptsAllowedStructuredClaimResolutionSeed」、「requestWithClaimSeed」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class CreateDisputeRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    // 所属模块：【案件核心与导入 / HTTP 接口层】「CreateDisputeRequestTest.setUpValidator()」。
    // 具体功能：「CreateDisputeRequestTest.setUpValidator()」：在每个测试场景运行前创建「validatorFactory.getValidator」、「Validation.buildDefaultValidatorFactory」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「CreateDisputeRequestTest.setUpValidator()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「CreateDisputeRequestTest.setUpValidator()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CreateDisputeRequestTest.setUpValidator()」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「CreateDisputeRequestTest.closeValidator()」。
    // 具体功能：「CreateDisputeRequestTest.closeValidator()」：作为测试辅助方法为“核对完整业务行为（场景方法「closeValidator」）”组装或读取「validatorFactory.close」，供本测试类的场景方法复用。
    // 上游调用：「CreateDisputeRequestTest.closeValidator()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「CreateDisputeRequestTest.closeValidator()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CreateDisputeRequestTest.closeValidator()」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「CreateDisputeRequestTest.validatesStructuredClaimResolutionSeedWithoutTreatingItAsAnExecutionAction()」。
    // 具体功能：「CreateDisputeRequestTest.validatesStructuredClaimResolutionSeedWithoutTreatingItAsAnExecutionAction()」：复现“校验业务契约（场景方法「validatesStructuredClaimResolutionSeedWithoutTreatingItAsAnExecutionAction」）”场景：驱动 「validator.validate」、「violation.getPropertyPath」、「requestWithClaimSeed」、「"x".repeat」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「USER」、「REFUND_NOW」、「-1」、「x」。
    // 上游调用：「CreateDisputeRequestTest.validatesStructuredClaimResolutionSeedWithoutTreatingItAsAnExecutionAction()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CreateDisputeRequestTest.validatesStructuredClaimResolutionSeedWithoutTreatingItAsAnExecutionAction()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CreateDisputeRequestTest.validatesStructuredClaimResolutionSeedWithoutTreatingItAsAnExecutionAction()」守住「案件核心与导入」的可执行规格，尤其防止 「USER」、「REFUND_NOW」、「-1」、「x」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void validatesStructuredClaimResolutionSeedWithoutTreatingItAsAnExecutionAction() {
        CreateDisputeRequest request =
                requestWithClaimSeed(
                        new IntakeLobbySeed.ClaimResolutionSeed(
                                "USER",
                                "REFUND_NOW",
                                new BigDecimal("-1"),
                                "x".repeat(513),
                                "用户希望退款。",
                                "我希望退款。"));

        var violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(
                        "claimResolutionSeed.requestedResolution",
                        "claimResolutionSeed.requestedAmount",
                        "claimResolutionSeed.requestedItems");
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「CreateDisputeRequestTest.acceptsAllowedStructuredClaimResolutionSeed()」。
    // 具体功能：「CreateDisputeRequestTest.acceptsAllowedStructuredClaimResolutionSeed()」：复现“核对完整业务行为（场景方法「acceptsAllowedStructuredClaimResolutionSeed」）”场景：驱动 「validator.validate」、「requestWithClaimSeed」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「USER」、「REFUND」、「299.00」、「物流显示签收但用户本人没有收到包裹，希望退款。」。
    // 上游调用：「CreateDisputeRequestTest.acceptsAllowedStructuredClaimResolutionSeed()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CreateDisputeRequestTest.acceptsAllowedStructuredClaimResolutionSeed()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CreateDisputeRequestTest.acceptsAllowedStructuredClaimResolutionSeed()」守住「案件核心与导入」的可执行规格，尤其防止 「USER」、「REFUND」、「299.00」、「物流显示签收但用户本人没有收到包裹，希望退款。」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void acceptsAllowedStructuredClaimResolutionSeed() {
        CreateDisputeRequest request =
                requestWithClaimSeed(
                        new IntakeLobbySeed.ClaimResolutionSeed(
                                "USER",
                                "REFUND",
                                new BigDecimal("299.00"),
                                "儿童手表 1 件",
                                "物流显示签收但用户本人没有收到包裹，希望退款。",
                                "我没收到包裹，希望退款"));

        assertThat(validator.validate(request)).isEmpty();
    }

    // 所属模块：【案件核心与导入 / HTTP 接口层】「CreateDisputeRequestTest.requestWithClaimSeed(ClaimResolutionSeed)」。
    // 具体功能：「CreateDisputeRequestTest.requestWithClaimSeed(ClaimResolutionSeed)」：作为测试辅助方法为“核对完整业务行为（场景方法「requestWithClaimSeed」）”组装或读取「CreateDisputeRequest」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「CreateDisputeRequestTest.requestWithClaimSeed(ClaimResolutionSeed)」由本测试类中的 「CreateDisputeRequestTest.validatesStructuredClaimResolutionSeedWithoutTreatingItAsAnExecutionAction」、「CreateDisputeRequestTest.acceptsAllowedStructuredClaimResolutionSeed」 调用。
    // 下游影响：「CreateDisputeRequestTest.requestWithClaimSeed(ClaimResolutionSeed)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CreateDisputeRequestTest.requestWithClaimSeed(ClaimResolutionSeed)」守住「案件核心与导入」的可执行规格，尤其防止 「ORDER-CLAIM-1」、「user-1」、「merchant-1」、「我没收到包裹，希望退款」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static CreateDisputeRequest requestWithClaimSeed(
            IntakeLobbySeed.ClaimResolutionSeed claimResolutionSeed) {
        return new CreateDisputeRequest(
                ActorRole.USER,
                "ORDER-CLAIM-1",
                null,
                null,
                "user-1",
                "merchant-1",
                "我没收到包裹，希望退款",
                claimResolutionSeed,
                null,
                List.of(),
                "WEB");
    }
}

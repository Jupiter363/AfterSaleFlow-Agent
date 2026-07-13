/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证房间消息，覆盖 「acceptsAttachmentOnlyMessagesWithinTheEvidenceEnvelopeContract」、「rejectsValuesThatCannotFitTheEvidenceEnvelopeContract」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.domain.MessageType;
import java.util.Collections;
import org.junit.jupiter.api.Test;

// 所属模块：【房间协作与权限 / 自动化测试层】类型「RoomMessageCommandTest」。
// 类型职责：集中验证房间消息的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「acceptsAttachmentOnlyMessagesWithinTheEvidenceEnvelopeContract」、「rejectsValuesThatCannotFitTheEvidenceEnvelopeContract」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class RoomMessageCommandTest {

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageCommandTest.acceptsAttachmentOnlyMessagesWithinTheEvidenceEnvelopeContract()」。
    // 具体功能：「RoomMessageCommandTest.acceptsAttachmentOnlyMessagesWithinTheEvidenceEnvelopeContract()」：复现“核对完整业务行为（场景方法「acceptsAttachmentOnlyMessagesWithinTheEvidenceEnvelopeContract」）”场景：驱动 「Collections.nCopies」、「assertThatCode」、「doesNotThrowAnyException」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_1」。
    // 上游调用：「RoomMessageCommandTest.acceptsAttachmentOnlyMessagesWithinTheEvidenceEnvelopeContract()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageCommandTest.acceptsAttachmentOnlyMessagesWithinTheEvidenceEnvelopeContract()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomMessageCommandTest.acceptsAttachmentOnlyMessagesWithinTheEvidenceEnvelopeContract()」守住「房间协作与权限」的可执行规格，尤其防止 「EVIDENCE_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void acceptsAttachmentOnlyMessagesWithinTheEvidenceEnvelopeContract() {
        assertThatCode(
                        () ->
                                new RoomMessageCommand(
                                        MessageType.PARTY_EVIDENCE_REFERENCE,
                                        "",
                                        Collections.nCopies(50, "EVIDENCE_1")))
                .doesNotThrowAnyException();
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomMessageCommandTest.rejectsValuesThatCannotFitTheEvidenceEnvelopeContract()」。
    // 具体功能：「RoomMessageCommandTest.rejectsValuesThatCannotFitTheEvidenceEnvelopeContract()」：复现“拒绝非法输入或越权操作（场景方法「rejectsValuesThatCannotFitTheEvidenceEnvelopeContract」）”场景：驱动 「Collections.nCopies」、「hasMessageContaining」、「isInstanceOf」、「"x".repeat」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「x」、「EVIDENCE_1」。
    // 上游调用：「RoomMessageCommandTest.rejectsValuesThatCannotFitTheEvidenceEnvelopeContract()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomMessageCommandTest.rejectsValuesThatCannotFitTheEvidenceEnvelopeContract()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomMessageCommandTest.rejectsValuesThatCannotFitTheEvidenceEnvelopeContract()」守住「房间协作与权限」的可执行规格，尤其防止 「x」、「EVIDENCE_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsValuesThatCannotFitTheEvidenceEnvelopeContract() {
        assertThatThrownBy(
                        () ->
                                new RoomMessageCommand(
                                        MessageType.PARTY_TEXT,
                                        "x".repeat(2_000_001),
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text exceeds transport limit");
        assertThatThrownBy(
                        () ->
                                new RoomMessageCommand(
                                        MessageType.PARTY_EVIDENCE_REFERENCE,
                                        null,
                                        Collections.nCopies(51, "EVIDENCE_1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attachment references exceed transport limit");
    }
}

/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义房间消息跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.room.domain.MessageType;
import java.util.List;
import java.util.Objects;

// 所属模块：【房间协作与权限 / 应用编排层】类型「RoomMessageCommand」。
// 类型职责：定义房间消息跨层传递时使用的不可变数据契约；本类型显式提供 「RoomMessageCommand」。
// 协作关系：主要由 「EvidenceSubmissionService.createSubmission」、「RoomMessageRequest.toCommand」、「EvidenceAgentTurnServiceTest.agentContractMismatchIsNotSilentlyDegraded」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record RoomMessageCommand(
        MessageType messageType,
        String text,
        List<String> attachmentRefs) {

    private static final int MAX_TEXT_LENGTH = 2_000_000;
    private static final int MAX_ATTACHMENT_REFS = 50;

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomMessageCommand.RoomMessageCommand(int,int,MessageType,String,List)」。
    // 具体功能：「RoomMessageCommand.RoomMessageCommand(int,int,MessageType,String,List)」：在不可变「RoomMessageCommand」写入组件前校验 「MAX_TEXT_LENGTH」(int)、「MAX_ATTACHMENT_REFS」(int)、「messageType」(MessageType)、「text」(String)、「attachmentRefs」(List)，非法输入会抛出 「IllegalArgumentException」；并通过 「Objects.requireNonNull」 做标准化或防御性复制。
    // 上游调用：「RoomMessageCommand.RoomMessageCommand(int,int,MessageType,String,List)」的上游创建点包括 「EvidenceSubmissionService.createSubmission」、「RoomMessageRequest.toCommand」、「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」。
    // 下游影响：「RoomMessageCommand.RoomMessageCommand(int,int,MessageType,String,List)」向下依次触达 「Objects.requireNonNull」。
    // 系统意义：「RoomMessageCommand.RoomMessageCommand(int,int,MessageType,String,List)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public RoomMessageCommand {
        Objects.requireNonNull(messageType, "messageType must not be null");
        if ((text == null || text.isBlank())
                && (attachmentRefs == null || attachmentRefs.isEmpty())) {
            throw new IllegalArgumentException("message requires text or attachment references");
        }
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("message text exceeds transport limit");
        }
        if (attachmentRefs != null && attachmentRefs.size() > MAX_ATTACHMENT_REFS) {
            throw new IllegalArgumentException("message attachment references exceed transport limit");
        }
        attachmentRefs = attachmentRefs == null ? List.of() : List.copyOf(attachmentRefs);
    }
}

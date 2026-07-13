/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义接待Confirmation跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.domain.model.RiskLevel;
import java.util.Objects;

// 所属模块：【房间协作与权限 / 应用编排层】类型「IntakeConfirmationCommand」。
// 类型职责：定义接待Confirmation跨层传递时使用的不可变数据契约；本类型显式提供 「IntakeConfirmationCommand」、「requireText」、「optionalText」。
// 协作关系：主要由 「IntakeConfirmationRequest.toCommand」、「IntakeRoomServiceIntegrationTest.acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline」、「IntakeRoomServiceIntegrationTest.notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake」、「IntakeRoomServiceTest.acceptedImportedIntakeClosesTheExistingRoomInsteadOfInsertingADuplicate」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record IntakeConfirmationCommand(
        boolean admissible,
        String disputeType,
        RiskLevel riskLevel,
        String confirmationNote) {

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeConfirmationCommand.IntakeConfirmationCommand(boolean,String,RiskLevel,String)」。
    // 具体功能：「IntakeConfirmationCommand.IntakeConfirmationCommand(boolean,String,RiskLevel,String)」：在不可变「IntakeConfirmationCommand」写入组件前校验 「admissible」(boolean)、「disputeType」(String)、「riskLevel」(RiskLevel)、「confirmationNote」(String)，并通过 「Objects.requireNonNull」、「requireText」、「optionalText」 做标准化或防御性复制。
    // 上游调用：「IntakeConfirmationCommand.IntakeConfirmationCommand(boolean,String,RiskLevel,String)」的上游创建点包括 「IntakeConfirmationRequest.toCommand」、「IntakeRoomServiceIntegrationTest.acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline」、「IntakeRoomServiceIntegrationTest.notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake」、「IntakeRoomServiceTest.acceptedIntakeInvitesBothPartiesAndOpensATwoHourEvidenceWindow」。
    // 下游影响：「IntakeConfirmationCommand.IntakeConfirmationCommand(boolean,String,RiskLevel,String)」向下依次触达 「Objects.requireNonNull」、「requireText」、「optionalText」。
    // 系统意义：「IntakeConfirmationCommand.IntakeConfirmationCommand(boolean,String,RiskLevel,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public IntakeConfirmationCommand {
        requireText(disputeType, "disputeType");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        confirmationNote = optionalText(confirmationNote);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeConfirmationCommand.requireText(String,String)」。
    // 具体功能：「IntakeConfirmationCommand.requireText(String,String)」：强制校验文本；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「IntakeConfirmationCommand.requireText(String,String)」的上游调用点包括 「IntakeConfirmationCommand.IntakeConfirmationCommand」。
    // 下游影响：「IntakeConfirmationCommand.requireText(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「IntakeConfirmationCommand.requireText(String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeConfirmationCommand.optionalText(String)」。
    // 具体功能：「IntakeConfirmationCommand.optionalText(String)」：构建可选文本，最终返回「String」。
    // 上游调用：「IntakeConfirmationCommand.optionalText(String)」的上游调用点包括 「IntakeConfirmationCommand.IntakeConfirmationCommand」。
    // 下游影响：「IntakeConfirmationCommand.optionalText(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「IntakeConfirmationCommand.optionalText(String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

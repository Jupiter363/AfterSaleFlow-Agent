package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomStart;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/**
 * 从通用 CaseProcess 合同到 typed Intake child 协议的只读绑定桥。
 *
 * <p>上游：{@link CaseProcessWorkflowImpl} 在启动、命令路由和领域事件派发前调用；Temporal 角色：Activity；
 * 下游：{@code IntakeChildBridgeActivitiesAdapter} 验证并生成 {@link IntakeRoomStart}、
 * {@link IntakeWorkflowCommand} 或 {@link IntakeDomainEventRef}，返回的 hash/revision 绑定是 child 调用和
 * workflow replay 的共同边界，不直接启动 child 或写入业务投影。
 */
@ActivityInterface
public interface IntakeChildBridgeActivities {

    /**
     * 上游：CaseProcessWorkflowImpl 的 room epoch provisioning 分支。
     *
     * <p>Temporal 角色：Activity；下游：adapter 将 {@link ProvisionRoomEpoch} 与 active-child authority 绑定为
     * {@link IntakeRoomStart}，供工作流只启动一次匹配的 Intake child。
     */
    @ActivityMethod(name = "BindIntakeChildStart")
    StartBinding bindStart(StartRequest request);

    /**
     * 上游：CaseProcessWorkflowImpl 向当前 Intake child 路由命令前的分支。
     *
     * <p>Temporal 角色：Activity；下游：adapter 产出带 source/request hash 与 revision 的
     * {@link IntakeWorkflowCommand}，这些坐标限制重放不能跨 child authority 派发。
     */
    @ActivityMethod(name = "BindIntakeChildCommand")
    CommandBinding bindCommand(CommandRequest request);

    /**
     * 上游：CaseProcessWorkflowImpl 向当前 Intake child 转交已提交领域事件前的分支。
     *
     * <p>Temporal 角色：Activity；下游：adapter 产出带 hash/revision 的 {@link IntakeDomainEventRef}，供 child
     * 事件处理与父 workflow 的 sequence/replay 校验使用。
     */
    @ActivityMethod(name = "BindIntakeChildDomainEvent")
    DomainEventBinding bindDomainEvent(DomainEventRequest request);

    record ActiveChildBinding(
            String schemaVersion,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            String selectionSchemaVersion,
            String caseWorkflowType,
            String caseWorkflowBuildId,
            String roomWorkflowType,
            String roomWorkflowBuildId) {

        public ActiveChildBinding {
            requireSchema(schemaVersion, "active-intake-child-binding.v1");
            requireIdentifier(tenantSurrogate, "tenantSurrogate");
            requireIdentifier(caseId, "caseId");
            requireNonNegative(roomEpoch, "roomEpoch");
            requirePositive(fencingToken, "fencingToken");
            requireIdentifier(selectionSchemaVersion, "selectionSchemaVersion");
            requireIdentifier(caseWorkflowType, "caseWorkflowType");
            requireIdentifier(caseWorkflowBuildId, "caseWorkflowBuildId");
            requireIdentifier(roomWorkflowType, "roomWorkflowType");
            requireIdentifier(roomWorkflowBuildId, "roomWorkflowBuildId");
        }
    }

    record StartRequest(
            String schemaVersion,
            ProvisionRoomEpoch provisioning,
            ActiveChildBinding activeBinding) {

        public StartRequest {
            requireSchema(schemaVersion, "intake-child-start-request.v1");
            Objects.requireNonNull(provisioning, "provisioning must not be null");
            Objects.requireNonNull(activeBinding, "activeBinding must not be null");
        }
    }

    record CommandRequest(
            String schemaVersion,
            CaseCommandRef command,
            ActiveChildBinding activeBinding) {

        public CommandRequest {
            requireSchema(schemaVersion, "intake-child-command-request.v1");
            Objects.requireNonNull(command, "command must not be null");
            Objects.requireNonNull(activeBinding, "activeBinding must not be null");
        }
    }

    record DomainEventRequest(
            String schemaVersion,
            CaseDomainEventRef event,
            ActiveChildBinding activeBinding) {

        public DomainEventRequest {
            requireSchema(schemaVersion, "intake-child-domain-event-request.v1");
            Objects.requireNonNull(event, "event must not be null");
            Objects.requireNonNull(activeBinding, "activeBinding must not be null");
        }
    }

    record StartBinding(
            String schemaVersion,
            ActiveChildBinding activeBinding,
            String provisioningRequestHash,
            IntakeRoomStart start) {

        public StartBinding {
            requireSchema(schemaVersion, "intake-child-start-binding.v1");
            Objects.requireNonNull(activeBinding, "activeBinding must not be null");
            requireHash(provisioningRequestHash, "provisioningRequestHash");
            Objects.requireNonNull(start, "start must not be null");
        }
    }

    record CommandBinding(
            String schemaVersion,
            ActiveChildBinding activeBinding,
            String sourcePayloadHash,
            String requestHash,
            long processRevision,
            long roomRevision,
            IntakeWorkflowCommand command) {

        public CommandBinding {
            requireSchema(schemaVersion, "intake-child-command-binding.v1");
            Objects.requireNonNull(activeBinding, "activeBinding must not be null");
            requireHash(sourcePayloadHash, "sourcePayloadHash");
            requireHash(requestHash, "requestHash");
            requireNonNegative(processRevision, "processRevision");
            requireNonNegative(roomRevision, "roomRevision");
            Objects.requireNonNull(command, "command must not be null");
        }
    }

    record DomainEventBinding(
            String schemaVersion,
            ActiveChildBinding activeBinding,
            String sourcePayloadHash,
            String requestHash,
            long processRevision,
            long roomRevision,
            IntakeDomainEventRef event) {

        public DomainEventBinding {
            requireSchema(schemaVersion, "intake-child-domain-event-binding.v1");
            Objects.requireNonNull(activeBinding, "activeBinding must not be null");
            requireHash(sourcePayloadHash, "sourcePayloadHash");
            requireHash(requestHash, "requestHash");
            requireNonNegative(processRevision, "processRevision");
            requireNonNegative(roomRevision, "roomRevision");
            Objects.requireNonNull(event, "event must not be null");
        }
    }

    private static void requireSchema(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("schemaVersion must be " + expected);
        }
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 value");
        }
    }

    private static void requirePositive(long value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}

package com.example.dispute.workflow.infrastructure.bootstrap;

import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.RoomEpochBootstrapOutboxEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class RoomEpochProvisioningMapper {

    private final ObjectMapper objectMapper;

    public RoomEpochProvisioningMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProvisionRoomEpoch fromLockedState(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            OffsetDateTime requestedAt) {
        requireProjectionTuple(epoch, projection);
        return new ProvisionRoomEpoch(
                ProvisionRoomEpoch.SCHEMA_VERSION,
                epoch.getId(),
                epoch.getTenantSurrogate(),
                epoch.getCaseId(),
                epoch.getRoomId(),
                epoch.getRoomType(),
                epoch.getRoomEpoch(),
                epoch.getProcessRevision(),
                epoch.getRoomRevision(),
                epoch.getFencingToken(),
                projection.getMacroPhase(),
                projection.getCurrentRoom(),
                projection.getRoomPhase(),
                epoch.getWriterMode(),
                epoch.getTemporalWorkflowId(),
                epoch.getRoomTemporalWorkflowId(),
                epoch.getSelectionSchemaVersion(),
                epoch.getProcessContractVersion(),
                epoch.getWorkflowType(),
                epoch.getTemporalBuildId(),
                epoch.getRoomWorkflowType(),
                epoch.getRoomWorkflowBuildId(),
                epoch.getGraphKey(),
                epoch.getGraphVersion(),
                epoch.getCheckpointSchemaVersion(),
                epoch.getStreamProtocol(),
                projection.getLastCommandSequence(),
                projection.getLastCaseEventSequence(),
                Math.incrementExact(projection.getLastCommandSequence()),
                Math.incrementExact(projection.getLastCaseEventSequence()),
                projection.getProjectedDeadlineAt() == null
                        ? null
                        : projection.getProjectedDeadlineAt().toInstant(),
                projection.getProjectionRef(),
                projection.getProjectionSha256(),
                Objects.requireNonNull(requestedAt, "requestedAt must not be null").toInstant());
    }

    public String toJson(ProvisionRoomEpoch command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("room epoch bootstrap payload cannot be serialized", exception);
        }
    }

    public ProvisionRoomEpoch fromOutbox(RoomEpochBootstrapOutboxEntity outbox) {
        ProvisionRoomEpoch command;
        try {
            command = objectMapper.readValue(outbox.getPayloadJson(), ProvisionRoomEpoch.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("room epoch bootstrap payload cannot be read", exception);
        }
        if (!command.payloadSha256().equals(outbox.getPayloadSha256())) {
            throw new IllegalStateException("room epoch bootstrap payload hash mismatch");
        }
        requireOutboxTuple(command, outbox);
        return command;
    }

    public void requireLockedState(
            ProvisionRoomEpoch command,
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            RoomEpochBootstrapOutboxEntity outbox) {
        requireOutboxTuple(command, outbox);
        requireProjectionTuple(epoch, projection);
        ProvisionRoomEpoch expected =
                fromLockedState(
                        epoch,
                        projection,
                        OffsetDateTime.ofInstant(command.requestedAt(), ZoneOffset.UTC));
        requireEqual(command, expected, "locked epoch payload");
    }

    public void requireReceipt(
            ProvisionRoomEpoch command,
            ProvisionRoomEpochReceipt receipt,
            RoomEpochBootstrapOutboxEntity outbox) {
        Objects.requireNonNull(receipt, "provisioning receipt must not be null");
        if (!receipt.matches(command)) {
            throw new IllegalStateException("receipt does not match the locked bootstrap payload");
        }
        requireEqual(receipt.provisioningSha256(), outbox.getPayloadSha256(), "receipt payload hash");
    }

    private static void requireProjectionTuple(
            CaseRoomEpochEntity epoch, CaseProcessProjectionEntity projection) {
        requireEqual(projection.getTenantSurrogate(), epoch.getTenantSurrogate(), "projection tenant");
        requireEqual(projection.getCaseId(), epoch.getCaseId(), "projection case");
        requireEqual(projection.getRoomEpoch(), epoch.getRoomEpoch(), "projection room epoch");
        requireEqual(projection.getFencingToken(), epoch.getFencingToken(), "projection fence");
        requireEqual(projection.getWriterMode(), epoch.getWriterMode(), "projection writer mode");
        requireEqual(projection.getProcessRevision(), epoch.getProcessRevision(), "projection process revision");
        requireEqual(projection.getTemporalWorkflowId(), epoch.getTemporalWorkflowId(), "projection workflow id");
        requireEqual(projection.getTemporalBuildId(), epoch.getTemporalBuildId(), "projection build id");
    }

    private static void requireOutboxTuple(
            ProvisionRoomEpoch command, RoomEpochBootstrapOutboxEntity outbox) {
        requireEqual(outbox.getEpochId(), command.epochId(), "outbox epoch id");
        requireEqual(outbox.getTenantSurrogate(), command.tenantSurrogate(), "outbox tenant");
        requireEqual(outbox.getCaseId(), command.caseId(), "outbox case");
        requireEqual(outbox.getRoomType(), command.roomType(), "outbox room type");
        requireEqual(outbox.getRoomEpoch(), command.roomEpoch(), "outbox room epoch");
        requireEqual(outbox.getFencingToken(), command.fencingToken(), "outbox fence");
        requireEqual(outbox.getWriterMode(), command.writerMode(), "outbox writer mode");
        requireEqual(outbox.getCaseWorkflowId(), command.caseWorkflowId(), "outbox case workflow id");
        requireEqual(outbox.getRoomWorkflowId(), command.roomWorkflowId(), "outbox room workflow id");
        requireEqual(outbox.getUpdateId(), command.updateId(), "outbox update id");
    }

    private static void requireEqual(Object actual, Object expected, String field) {
        if (!Objects.equals(actual, expected)) {
            throw new IllegalStateException(field + " does not match the bootstrap tuple");
        }
    }

    private static void requireEqual(long actual, long expected, String field) {
        if (actual != expected) {
            throw new IllegalStateException(field + " does not match the bootstrap tuple");
        }
    }
}

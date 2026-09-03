package com.example.dispute.workflow.api;

import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CaseCommandRequest(
        @NotNull CommandType commandType,
        @NotNull RoomType roomType,
        @NotNull @PositiveOrZero Long roomEpoch,
        @NotNull @Valid PayloadReference payloadRef,
        @NotNull @PositiveOrZero Long expectedProcessRevision,
        @NotNull Instant deadlineAt) {

    AcceptCaseCommand toCommand() {
        return new AcceptCaseCommand(
                commandType,
                roomType,
                roomEpoch,
                payloadRef.toReference(),
                expectedProcessRevision,
                deadlineAt);
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object ignored) {
        throw new IllegalArgumentException("unknown command field: " + name);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PayloadReference(
            @NotBlank
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    String schemaVersion,
            @NotBlank
                    @Size(max = 1024)
                    @Pattern(regexp = "^(s3|minio|urn):.+")
                    String uri,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sha256,
            @NotNull @PositiveOrZero @Max(1_073_741_824L) Long sizeBytes) {

        PayloadRef toReference() {
            return new PayloadRef(schemaVersion, uri, sha256, sizeBytes);
        }

        @JsonAnySetter
        public void rejectUnknownField(String name, Object ignored) {
            throw new IllegalArgumentException("unknown payload field: " + name);
        }
    }
}

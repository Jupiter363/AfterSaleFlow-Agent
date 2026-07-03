package com.example.dispute.hearing.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import java.util.Map;

public final class SettlementVersionConflictException extends BusinessException {

    public SettlementVersionConflictException(
            String caseId, int requestedVersion, int currentVersion) {
        super(
                ErrorCode.CASE_STATUS_INVALID,
                "settlement version is no longer current",
                Map.of(
                        "case_id", caseId,
                        "requested_version", requestedVersion,
                        "current_version", currentVersion));
    }
}

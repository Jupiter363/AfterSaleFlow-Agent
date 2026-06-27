package com.example.dispute.common.exception;

import com.example.dispute.common.api.ErrorCode;
import java.util.Map;

public final class ApprovalException extends BusinessException {

    public ApprovalException(String message) {
        super(ErrorCode.APPROVAL_REQUIRED, message, Map.of());
    }
}

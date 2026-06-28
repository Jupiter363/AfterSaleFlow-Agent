package com.example.dispute.common.exception;

import com.example.dispute.common.api.ErrorCode;
import java.util.Map;

public final class CaseClosureException extends BusinessException {

    public CaseClosureException(String message, Map<String, Object> details) {
        super(ErrorCode.CASE_STATUS_INVALID, message, details);
    }
}

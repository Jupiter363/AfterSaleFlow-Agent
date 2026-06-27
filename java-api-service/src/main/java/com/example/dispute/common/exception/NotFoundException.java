package com.example.dispute.common.exception;

import com.example.dispute.common.api.ErrorCode;
import java.util.Map;

public final class NotFoundException extends BusinessException {

    public NotFoundException(
            ErrorCode errorCode, String message, Map<String, Object> details) {
        super(errorCode, message, details);
    }
}

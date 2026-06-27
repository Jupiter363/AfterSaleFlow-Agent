package com.example.dispute.common.exception;

import com.example.dispute.common.api.ErrorCode;
import java.util.Map;

public final class BadRequestException extends BusinessException {

    public BadRequestException(String message, Map<String, Object> details) {
        super(ErrorCode.INVALID_ARGUMENT, message, details);
    }
}

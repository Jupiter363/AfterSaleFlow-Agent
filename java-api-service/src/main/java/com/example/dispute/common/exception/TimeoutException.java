package com.example.dispute.common.exception;

import com.example.dispute.common.api.ErrorCode;
import java.util.Map;

public final class TimeoutException extends BusinessException {

    public TimeoutException(String message) {
        super(ErrorCode.EXTERNAL_SERVICE_TIMEOUT, message, Map.of());
    }
}

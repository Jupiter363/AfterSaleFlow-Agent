package com.example.dispute.common.exception;

import com.example.dispute.common.api.ErrorCode;
import java.util.Map;

public final class AgentExecutionException extends BusinessException {

    public AgentExecutionException(
            ErrorCode errorCode, String message, Map<String, Object> details) {
        super(errorCode, message, details);
    }
}

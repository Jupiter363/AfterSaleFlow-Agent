package com.example.dispute.config;

import com.example.dispute.common.api.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public final class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityFailureWriter failureWriter;

    public JsonAccessDeniedHandler(SecurityFailureWriter failureWriter) {
        this.failureWriter = failureWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        failureWriter.write(request, response, ErrorCode.FORBIDDEN, "access denied");
    }
}

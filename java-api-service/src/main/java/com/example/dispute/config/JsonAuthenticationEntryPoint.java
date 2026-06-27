package com.example.dispute.config;

import com.example.dispute.common.api.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public final class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityFailureWriter failureWriter;

    public JsonAuthenticationEntryPoint(SecurityFailureWriter failureWriter) {
        this.failureWriter = failureWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException, ServletException {
        failureWriter.write(
                request, response, ErrorCode.UNAUTHORIZED, "authentication required");
    }
}

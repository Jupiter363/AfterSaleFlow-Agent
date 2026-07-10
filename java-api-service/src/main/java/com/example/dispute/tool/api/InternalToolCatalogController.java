package com.example.dispute.tool.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.tool.application.ToolDefinition;
import com.example.dispute.tool.application.ToolRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/tools")
public class InternalToolCatalogController {

    private final ToolRegistry toolRegistry;
    private final Clock clock;

    public InternalToolCatalogController(ToolRegistry toolRegistry, Clock clock) {
        this.toolRegistry = toolRegistry;
        this.clock = clock;
    }

    @GetMapping("/execution")
    public ApiResponse<List<ToolDefinition>> executionTools(HttpServletRequest request) {
        return ApiResponse.success(
                toolRegistry.definitions(),
                (String) request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE),
                (String) request.getAttribute(TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }
}

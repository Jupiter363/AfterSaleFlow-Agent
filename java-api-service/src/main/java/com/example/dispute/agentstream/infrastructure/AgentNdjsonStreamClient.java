/*
 * 所属模块：Agent 流式运行。
 * 文件职责：把Python Agent NDJSON 流式协议请求适配为受超时和协议校验约束的外部 HTTP 调用。
 * 业务链路：核心入口/契约为 「stream」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.infrastructure;

import com.example.dispute.agentstream.application.AgentRunExecutionDescriptor;
import com.example.dispute.agentstream.application.AgentStreamClient;
import com.example.dispute.agentstream.application.AgentStreamFrame;
import com.example.dispute.agentstream.application.AgentStreamProtocolException;
import com.example.dispute.agentstream.application.AgentStreamTransportException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

// 所属模块：【Agent 流式运行 / 外部集成层】类型「AgentNdjsonStreamClient」。
// 类型职责：把Python Agent NDJSON 流式协议请求适配为受超时和协议校验约束的外部 HTTP 调用；本类型显式提供 「AgentNdjsonStreamClient」、「stream」、「parse」、「visibleDelta」、「usage」、「finalFrame」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class AgentNdjsonStreamClient implements AgentStreamClient {

    private static final String SCHEMA_VERSION = "agent_stream.v1";
    private static final Set<String> EVENT_TYPES =
            Set.of("start", "visible_delta", "usage", "final", "error");
    private static final int MAX_EVENT_LINE_CHARS = 4 * 1024 * 1024;
    private static final int MAX_DELTA_CHARS = 32 * 1024;
    private static final int MAX_VISIBLE_CHARS = 512 * 1024;
    private static final Set<String> V2_ENVELOPE_FIELDS =
            Set.of(
                    "schema_version",
                    "run_id",
                    "attempt_id",
                    "sequence_no",
                    "event_type",
                    "audience",
                    "occurred_at",
                    "payload");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final String serviceSecret;
    private final Duration requestTimeout;

    // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.AgentNdjsonStreamClient(AppProperties,ObjectMapper)」。
    // 具体功能：「AgentNdjsonStreamClient.AgentNdjsonStreamClient(AppProperties,ObjectMapper)」：通过构造器接收 「properties」(AppProperties)、「objectMapper」(ObjectMapper) 并保存为「AgentNdjsonStreamClient」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AgentNdjsonStreamClient.AgentNdjsonStreamClient(AppProperties,ObjectMapper)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供。
    // 下游影响：「AgentNdjsonStreamClient.AgentNdjsonStreamClient(AppProperties,ObjectMapper)」向下依次触达 「HttpClient.newBuilder」、「Duration.ofMillis」、「URI.create」、「properties.agent」。
    // 系统意义：「AgentNdjsonStreamClient.AgentNdjsonStreamClient(AppProperties,ObjectMapper)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentNdjsonStreamClient(AppProperties properties, ObjectMapper objectMapper) {
        AppProperties.Integration integration = properties.agent();
        this.requestTimeout = Duration.ofMillis(integration.timeoutMs());
        this.httpClient =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(requestTimeout)
                        .build();
        this.objectMapper = objectMapper;
        this.baseUri = URI.create(withTrailingSlash(integration.baseUrl()));
        this.serviceSecret = integration.serviceSecret();
    }

    // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.stream(AgentRunExecutionDescriptor,Consumer)」。
    // 具体功能：「AgentNdjsonStreamClient.stream(AgentRunExecutionDescriptor,Consumer)」：使用服务密钥、Run ID、Trace ID 和 HTTP/1.1 向 Python 发起 NDJSON 请求；校验 HTTP 状态与回显 Run ID，逐行限制大小、解析并暂存终态帧，只有整条流通过协议完整性检查后才把 final/error 交给下游，最终返回「void」。
    // 上游调用：「AgentNdjsonStreamClient.stream(AgentRunExecutionDescriptor,Consumer)」由使用「AgentNdjsonStreamClient」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentNdjsonStreamClient.stream(AgentRunExecutionDescriptor,Consumer)」向下依次触达 「httpClient.send」、「HttpRequest.newBuilder」、「Thread.currentThread」、「baseUri.resolve」。
    // 系统意义：「AgentNdjsonStreamClient.stream(AgentRunExecutionDescriptor,Consumer)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    @Override
    public void stream(
            AgentRunExecutionDescriptor run, Consumer<AgentStreamFrame> sink) {
        // 协议准备阶段：endpoint 只能来自服务端 OperationRegistry，调用方不能传任意 URL；
        // Run/Trace/Request ID 同时放入请求头，便于 Python 回显绑定并贯穿跨服务日志。
        HttpRequest request =
                HttpRequest.newBuilder(baseUri.resolve(withoutLeadingSlash(run.endpoint())))
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/x-ndjson")
                        .header("X-Service-Secret", serviceSecret)
                        .header("X-Agent-Run-Id", run.runId())
                        .header(TraceIdFilter.TRACE_HEADER, run.traceId())
                        .header(TraceIdFilter.REQUEST_HEADER, run.requestId())
                        .header("X-Role", "SYSTEM")
                        .POST(HttpRequest.BodyPublishers.ofString(run.requestJson(), StandardCharsets.UTF_8))
                        .build();

        HttpResponse<java.io.InputStream> response;
        try {
            response =
                    httpClient.send(
                            request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentStreamTransportException(
                    "agent stream request interrupted", true, exception);
        } catch (IOException exception) {
            throw new AgentStreamTransportException(
                    "agent stream connection failed", true, exception);
        }

        // 在读取可能很大的响应体之前先验证 HTTP 状态和 Run ID。
        // 若 Python/代理层串回了其他运行的流，必须立即关闭，不能让跨案件内容进入当前 sink。
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            closeQuietly(response.body());
            boolean retryable = response.statusCode() >= 500 || response.statusCode() == 429;
            throw new AgentStreamTransportException(
                    "agent stream returned HTTP " + response.statusCode(), retryable);
        }
        String echoedRunId =
                response.headers().firstValue("X-Agent-Run-Id").orElse("");
        if (!run.runId().equals(echoedRunId)) {
            closeQuietly(response.body());
            throw new AgentStreamProtocolException(
                    "agent stream response run id does not match request");
        }

        ProtocolState state =
                new ProtocolState(
                        run.runId(), run.protocolOperation(), run.visibleFieldPaths());
        // final/error 暂不立即交给下游。只有后续确认流正常结束且状态机完整，
        // 终态才有资格触发领域 Finalizer；否则截断流可能把半成品错误地写成正式结果。
        AgentStreamFrame terminalFrame = null;
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.length() > MAX_EVENT_LINE_CHARS) {
                    throw new AgentStreamProtocolException("agent stream event exceeds size limit");
                }
                AgentStreamFrame frame = parse(line, state);
                if ("final".equals(frame.event()) || "error".equals(frame.event())) {
                    terminalFrame = frame;
                } else {
                    sink.accept(frame);
                }
            }
        } catch (AgentStreamProtocolException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new AgentStreamTransportException(
                    "agent stream ended unexpectedly", true, failure);
        } catch (RuntimeException failure) {
            throw new AgentStreamProtocolException(
                    "agent stream event handling failed", failure);
        }
        state.assertComplete();
        if (terminalFrame == null) {
            throw new AgentStreamProtocolException(
                    "agent stream ended without a terminal payload");
        }
        // 整条 NDJSON 已通过顺序、大小、Schema 和终态完整性校验，现在才发布终态。
        // visible_delta 可以边到边展示，但它本身永远不能替代最终结构化业务结果。
        sink.accept(terminalFrame);
    }

    // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.parse(String,ProtocolState)」。
    // 具体功能：「AgentNdjsonStreamClient.parse(String,ProtocolState)」：把单行 JSON 解析为协议帧，校验 schema_version、run_id、严格递增 sequence、事件白名单和 operation，再按 start/visible_delta/usage/final/error 分派到专用解析器，最终返回「AgentStreamFrame」。
    // 上游调用：「AgentNdjsonStreamClient.parse(String,ProtocolState)」的上游调用点包括 「AgentNdjsonStreamClient.stream」。
    // 下游影响：「AgentNdjsonStreamClient.parse(String,ProtocolState)」向下依次触达 「objectMapper.readTree」、「node.isObject」、「node.has」、「state.acceptEnvelope」；计算结果以「AgentStreamFrame」交给调用方。
    // 系统意义：「AgentNdjsonStreamClient.parse(String,ProtocolState)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private AgentStreamFrame parse(String line, ProtocolState state) {
        JsonNode node;
        try {
            node = objectMapper.readTree(line);
        } catch (JsonProcessingException exception) {
            throw new AgentStreamProtocolException("agent stream emitted invalid JSON", exception);
        }
        if (!node.isObject()) {
            throw new AgentStreamProtocolException("agent stream event must be a JSON object");
        }
        requireText(node, "schema_version", SCHEMA_VERSION);
        requireText(node, "run_id", state.runId);
        if (!node.has("sequence") || !node.get("sequence").isIntegralNumber()) {
            throw new AgentStreamProtocolException(
                    "agent stream event has invalid sequence");
        }
        long sequence = node.path("sequence").asLong(-1);
        if (sequence <= state.lastSequence) {
            throw new AgentStreamProtocolException("agent stream sequence is not strictly increasing");
        }
        String event = node.path("type").asText("");
        if (!EVENT_TYPES.contains(event)) {
            throw new AgentStreamProtocolException("unsupported agent stream event type");
        }
        state.acceptEnvelope(sequence, event);
        requiredText(node, "timestamp");
        if ("start".equals(event) || "final".equals(event)) {
            requireText(node, "operation", state.protocolOperation);
        }

        String nodeName = nullableText(node.get("node_name"));
        return switch (event) {
            case "start" ->
                    new AgentStreamFrame(
                            sequence,
                            event,
                            nodeName,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null);
            case "visible_delta" -> visibleDelta(node, sequence, nodeName, state);
            case "usage" -> usage(node, sequence, nodeName);
            case "final" -> finalFrame(node, sequence, nodeName);
            case "error" -> errorFrame(node, sequence, nodeName);
            default -> throw new AgentStreamProtocolException("unreachable event type");
        };
    }

    /** Strict parser for the frozen public v2 envelope. It never accepts raw model JSON. */
    public static AgentStreamEvent parseV2Line(
            ObjectMapper mapper, String line, V2ProtocolState state) {
        return parseVersionedLine(mapper, line, state, "agent-stream.v2");
    }

    /** Strict parser for the v3 frame stream. Public text deltas remain transient until a
     * complete frame is committed by the Java gateway. */
    public static AgentStreamEvent parseV3Line(
            ObjectMapper mapper, String line, V2ProtocolState state) {
        return parseVersionedLine(mapper, line, state, "agent-stream.v3");
    }

    private static AgentStreamEvent parseVersionedLine(
            ObjectMapper mapper, String line, V2ProtocolState state, String expectedSchema) {
        JsonNode node;
        try {
            node = mapper.readTree(line);
        } catch (JsonProcessingException exception) {
            throw new AgentStreamProtocolException("agent stream v2 emitted invalid JSON", exception);
        }
        if (node == null || !node.isObject()) {
            throw new AgentStreamProtocolException("agent stream v2 event must be an object");
        }
        rejectUnknown(node, V2_ENVELOPE_FIELDS, "v2 envelope");
        String runId = requiredV2Identifier(node, "run_id");
        String attemptId = requiredV2Identifier(node, "attempt_id");
        if (!expectedSchema.equals(node.path("schema_version").asText())
                || !state.runId.equals(runId)
                || !state.attemptId.equals(attemptId)) {
            throw new AgentStreamProtocolException("agent stream v2 identity does not match");
        }
        if (!node.path("sequence_no").isIntegralNumber()) {
            throw new AgentStreamProtocolException("agent stream v2 sequence is invalid");
        }
        long sequence = node.path("sequence_no").asLong(-1);
        if (sequence < 0) {
            throw new AgentStreamProtocolException("agent stream v2 sequence is invalid");
        }
        StreamEventType eventType;
        Audience audience;
        Instant occurredAt;
        try {
            eventType = StreamEventType.fromWire(node.path("event_type").asText());
            audience = Audience.valueOf(node.path("audience").asText());
            occurredAt = Instant.parse(node.path("occurred_at").asText());
        } catch (RuntimeException exception) {
            throw new AgentStreamProtocolException("agent stream v2 envelope is invalid", exception);
        }
        JsonNode payload = node.path("payload");
        if (!payload.isObject()) {
            throw new AgentStreamProtocolException("agent stream v2 payload must be an object");
        }
        rejectUnknown(payload, allowedPayloadFields(eventType), expectedSchema + " " + eventType.wireValue() + " payload");
        validateVersionedPayload(eventType, payload, state, expectedSchema);
        if (audience != state.audience) {
            throw new AgentStreamProtocolException("agent stream v2 audience does not match");
        }
        state.accept(sequence, eventType);
        try {
            return mapper.treeToValue(node, AgentStreamEvent.class);
        } catch (JsonProcessingException exception) {
            throw new AgentStreamProtocolException("agent stream v2 payload is invalid", exception);
        }
    }

    /** Adapts only already-allowlisted V1 preview frames. Raw V1 final JSON is never promoted. */
    public static AgentStreamEvent adaptV1Frame(
            AgentStreamFrame frame,
            String runId,
            String attemptId,
            Audience audience,
            String startNode,
            Set<String> visibleFieldPaths,
            Instant occurredAt) {
        if (frame == null || frame.sequence() < 0 || occurredAt == null) {
            throw new AgentStreamProtocolException("V1 adapter received an invalid envelope");
        }
        runId = requireV2Identifier(runId, "run_id");
        attemptId = requireV2Identifier(attemptId, "attempt_id");
        java.util.Objects.requireNonNull(audience, "audience");
        visibleFieldPaths = Set.copyOf(
                java.util.Objects.requireNonNull(visibleFieldPaths, "visibleFieldPaths"));
        AgentStreamEvent.Payload payload;
        StreamEventType eventType;
        switch (frame.event()) {
            case "start" -> {
                eventType = StreamEventType.ATTEMPT_STARTED;
                payload = payload(
                        requireV2Identifier(startNode, "node"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
            }
            case "visible_delta" -> {
                if (!visibleFieldPaths.contains(frame.fieldPath())) {
                    throw new AgentStreamProtocolException(
                            "V1 adapter rejected a non-public field");
                }
                if (frame.delta() == null
                        || frame.delta().isBlank()
                        || frame.delta().length() > 4096) {
                    throw new AgentStreamProtocolException(
                            "V1 adapter received an invalid visible delta");
                }
                eventType = StreamEventType.VISIBLE_DELTA;
                payload = payload(
                        requireV2Identifier(frame.nodeName(), "node"),
                        requireV2Identifier(frame.fieldPath(), "field"),
                        frame.delta(), null,
                        null, null, null, null, null, null);
            }
            case "usage" -> {
                JsonNode usageNode = frame.usage();
                long input = token(usageNode, "input_tokens", "prompt_tokens");
                long output = token(usageNode, "output_tokens", "completion_tokens");
                long total = usageNode != null && usageNode.has("total_tokens")
                        ? usageNode.path("total_tokens").asLong()
                        : input + output;
                if (input < 0 || output < 0 || total < 0) {
                    throw new AgentStreamProtocolException("V1 adapter received invalid usage");
                }
                eventType = StreamEventType.USAGE;
                payload = payload(null, null, null, new Usage(input, output, total),
                        null, null, null, null, null, null);
            }
            case "error" -> {
                if (frame.error() == null) {
                    throw new AgentStreamProtocolException(
                            "V1 adapter received an invalid error");
                }
                eventType = StreamEventType.ERROR;
                payload = payload(null, null, null, null, null, null, null, null,
                        requireV2Identifier(frame.error().code(), "error_code"),
                        frame.error().retryable());
            }
            case "final" -> throw new AgentStreamProtocolException(
                    "V1 final requires a persisted result reference before V2 projection");
            default -> throw new AgentStreamProtocolException("unsupported V1 adapter event");
        }
        return new AgentStreamEvent(
                "agent-stream.v2",
                runId,
                attemptId,
                frame.sequence(),
                eventType,
                audience,
                occurredAt,
                payload);
    }

    private static AgentStreamEvent.Payload payload(
            String node,
            String field,
            String delta,
            Usage usage,
            String reasonCode,
            String resetAttemptId,
            String finalResultRef,
            String finalResultHash,
            String errorCode,
            Boolean retryable) {
        return new AgentStreamEvent.Payload(
                node,
                field,
                delta,
                usage,
                reasonCode,
                resetAttemptId,
                finalResultRef,
                finalResultHash,
                errorCode,
                retryable);
    }

    private static long token(JsonNode usage, String currentName, String legacyName) {
        if (usage == null || !usage.isObject()) {
            throw new AgentStreamProtocolException("V1 adapter received invalid usage");
        }
        JsonNode value = usage.has(currentName) ? usage.get(currentName) : usage.get(legacyName);
        if (value == null || !value.canConvertToLong()) {
            throw new AgentStreamProtocolException("V1 adapter received invalid usage");
        }
        return value.asLong();
    }

    private static void validateVersionedPayload(
            StreamEventType eventType,
            JsonNode payload,
            V2ProtocolState state,
            String schemaVersion) {
        switch (eventType) {
            case ATTEMPT_STARTED -> requiredV2Identifier(payload, "node");
            case VISIBLE_DELTA -> {
                String node = requiredV2Identifier(payload, "node");
                String field = requiredV2Identifier(payload, "field");
                if (!state.allowsVisibleField(node, field)) {
                    throw new AgentStreamProtocolException(
                            "agent stream v2 attempted to expose a non-public field");
                }
                String delta = requiredV2VisibleDelta(payload);
                if (delta.length() > 4096
                        || state.visibleCharacters + delta.length() > MAX_VISIBLE_CHARS) {
                    throw new AgentStreamProtocolException("agent stream v2 visible output exceeds limit");
                }
                state.visibleCharacters += delta.length();
            }
            case PUBLIC_FRAME_START -> {
                requireV3(schemaVersion);
                String frameId = requiredV2Identifier(payload, "frame_id");
                int frameSequence = requiredV3Int(payload, "frame_sequence", 1, 128);
                String frameType = requiredV2Identifier(payload, "frame_type");
                JsonNode publicHeader = payload.get("public_header");
                if (publicHeader == null || !publicHeader.isObject()) {
                    throw new AgentStreamProtocolException(
                            "agent stream v3 public frame header is invalid");
                }
                state.startFrame(frameId, frameSequence, frameType);
            }
            case PUBLIC_TEXT_DELTA -> {
                requireV3(schemaVersion);
                String frameId = requiredV2Identifier(payload, "frame_id");
                int frameSequence = requiredV3Int(payload, "frame_sequence", 1, 128);
                int deltaIndex = requiredV3Int(payload, "delta_index", 0, Integer.MAX_VALUE);
                String delta = requiredV2VisibleDelta(payload);
                if (delta.length() > 4096
                        || state.visibleCharacters + delta.length() > MAX_VISIBLE_CHARS) {
                    throw new AgentStreamProtocolException(
                            "agent stream v3 public output exceeds limit");
                }
                state.acceptFrameText(frameId, frameSequence, deltaIndex, delta.length());
                state.visibleCharacters += delta.length();
            }
            case ACTIVE_FRAME_SNAPSHOT -> {
                requireV3(schemaVersion);
                String frameId = requiredV2Identifier(payload, "frame_id");
                int frameSequence = requiredV3Int(payload, "frame_sequence", 1, 128);
                int deltaIndex = requiredV3Int(payload, "delta_index", 0, Integer.MAX_VALUE);
                JsonNode text = payload.get("public_text");
                if (text == null || !text.isTextual()
                        || text.textValue().length() > MAX_VISIBLE_CHARS) {
                    throw new AgentStreamProtocolException(
                            "agent stream v3 active frame snapshot is invalid");
                }
                state.acceptFrameSnapshot(
                        frameId, frameSequence, deltaIndex, text.textValue().length());
            }
            case PUBLIC_FRAME_COMMITTED -> {
                requireV3(schemaVersion);
                String frameId = requiredV2Identifier(payload, "frame_id");
                int frameSequence = requiredV3Int(payload, "frame_sequence", 1, 128);
                requiredV3Cursor(payload, frameSequence, "FRAME");
                requiredV3Sha(payload, "header_sha256");
                requiredV3Sha(payload, "public_text_sha256");
                requiredV3Sha(payload, "frame_sha256");
                int chars = requiredV3Int(payload, "public_text_chars", 0, MAX_VISIBLE_CHARS);
                state.commitFrame(frameId, frameSequence, chars);
            }
            case PUBLIC_FRAME_INTERRUPTED -> {
                requireV3(schemaVersion);
                String frameId = requiredV2Identifier(payload, "frame_id");
                int frameSequence = requiredV3Int(payload, "frame_sequence", 1, 128);
                requiredV3Cursor(payload, frameSequence, "INTERRUPTED");
                requiredV2Identifier(payload, "reason_code");
                JsonNode text = payload.get("public_text");
                if (text == null || !text.isTextual()
                        || text.textValue().length() > MAX_VISIBLE_CHARS) {
                    throw new AgentStreamProtocolException(
                            "agent stream v3 interrupted frame text is invalid");
                }
                state.interruptFrame(frameId, frameSequence, text.textValue().length());
            }
            case USAGE -> {
                JsonNode usage = payload.path("usage");
                rejectUnknown(
                        usage,
                        Set.of("input_tokens", "output_tokens", "total_tokens"),
                        "v2 usage");
                if (!usage.isObject()
                        || !usage.path("input_tokens").isIntegralNumber()
                        || !usage.path("output_tokens").isIntegralNumber()
                        || !usage.path("total_tokens").isIntegralNumber()
                        || usage.path("input_tokens").asLong() < 0
                        || usage.path("output_tokens").asLong() < 0
                        || usage.path("total_tokens").asLong() < 0) {
                    throw new AgentStreamProtocolException("agent stream v2 usage is invalid");
                }
            }
            case ATTEMPT_ABORTED -> requiredV2Identifier(payload, "reason_code");
            case ATTEMPT_RESET -> {
                String resetAttemptId = requiredV2Identifier(payload, "reset_attempt_id");
                if (state.attemptId.equals(resetAttemptId)) {
                    throw new AgentStreamProtocolException(
                            "agent stream v2 reset must reference an older attempt");
                }
                requiredV2Identifier(payload, "reason_code");
            }
            case FINAL -> {
                String reference = requiredV2Text(payload, "final_result_ref");
                String hash = requiredV2Text(payload, "final_result_hash");
                if (!validV2ResultReference(reference)
                        || !hash.matches("[0-9a-f]{64}")) {
                    throw new AgentStreamProtocolException("agent stream v2 final reference is invalid");
                }
            }
            case ERROR -> {
                requiredV2Identifier(payload, "error_code");
                if (!payload.path("retryable").isBoolean()) {
                    throw new AgentStreamProtocolException("agent stream v2 retryable flag is invalid");
                }
            }
        }
    }

    private static Set<String> allowedPayloadFields(StreamEventType eventType) {
        return switch (eventType) {
            case ATTEMPT_STARTED -> Set.of("node");
            case VISIBLE_DELTA -> Set.of("node", "field", "delta");
            case PUBLIC_FRAME_START ->
                    Set.of("frame_id", "frame_sequence", "frame_type", "public_header");
            case PUBLIC_TEXT_DELTA ->
                    Set.of("frame_id", "frame_sequence", "delta_index", "delta");
            case ACTIVE_FRAME_SNAPSHOT ->
                    Set.of("frame_id", "frame_sequence", "delta_index", "public_text");
            case PUBLIC_FRAME_COMMITTED ->
                    Set.of(
                            "frame_id", "frame_sequence", "durable_cursor",
                            "header_sha256", "public_text_sha256", "frame_sha256",
                            "public_text_chars");
            case PUBLIC_FRAME_INTERRUPTED ->
                    Set.of(
                            "frame_id", "frame_sequence", "durable_cursor",
                            "reason_code", "public_text");
            case USAGE -> Set.of("usage");
            case ATTEMPT_ABORTED -> Set.of("reason_code");
            case ATTEMPT_RESET -> Set.of("reset_attempt_id", "reason_code");
            case FINAL -> Set.of("final_result_ref", "final_result_hash");
            case ERROR -> Set.of("error_code", "retryable");
        };
    }

    private static void requireV3(String schemaVersion) {
        if (!"agent-stream.v3".equals(schemaVersion)) {
            throw new AgentStreamProtocolException(
                    "frame stream events require agent-stream.v3");
        }
    }

    private static int requiredV3Int(
            JsonNode payload, String field, int minimum, int maximum) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new AgentStreamProtocolException("agent stream v3 " + field + " is invalid");
        }
        int result = value.intValue();
        if (result < minimum || result > maximum) {
            throw new AgentStreamProtocolException("agent stream v3 " + field + " is invalid");
        }
        return result;
    }

    private static String requiredV3Sha(JsonNode payload, String field) {
        String value = requiredV2Text(payload, field);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new AgentStreamProtocolException("agent stream v3 " + field + " is invalid");
        }
        return value;
    }

    private static String requiredV3Cursor(
            JsonNode payload, int frameSequence, String disposition) {
        String cursor = requiredV2Text(payload, "durable_cursor");
        String suffix = ":" + disposition + ":" + frameSequence;
        if (!cursor.startsWith("v3:") || !cursor.endsWith(suffix)) {
            throw new AgentStreamProtocolException(
                    "agent stream v3 durable cursor is invalid");
        }
        return cursor;
    }

    private static String requiredV2Text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new AgentStreamProtocolException("agent stream v2 is missing " + field);
        }
        return value.asText();
    }

    private static String requiredV2VisibleDelta(JsonNode payload) {
        JsonNode value = payload.get("delta");
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new AgentStreamProtocolException("agent stream v2 is missing delta");
        }
        return value.asText();
    }

    private static String requiredV2Identifier(JsonNode node, String field) {
        return requireV2Identifier(requiredV2Text(node, field), field);
    }

    private static String requireV2Identifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new AgentStreamProtocolException(
                    "agent stream v2 " + field + " is not a valid identifier");
        }
        return value;
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String scope) {
        Set<String> unknown = new HashSet<>();
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                unknown.add(field);
            }
        });
        if (!unknown.isEmpty()) {
            throw new AgentStreamProtocolException(scope + " contains unknown fields: " + unknown);
        }
    }

    // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.visibleDelta(JsonNode,long,String,ProtocolState)」。
    // 具体功能：「AgentNdjsonStreamClient.visibleDelta(JsonNode,long,String,ProtocolState)」：校验增量所属 node、字段路径白名单、非空文本、单帧 32 KiB 与累计 512 KiB 上限，仅把允许面向用户公开的文本转换为 visible_delta 帧，最终返回「AgentStreamFrame」。
    // 上游调用：「AgentNdjsonStreamClient.visibleDelta(JsonNode,long,String,ProtocolState)」的上游调用点包括 「AgentNdjsonStreamClient.parse」。
    // 下游影响：「AgentNdjsonStreamClient.visibleDelta(JsonNode,long,String,ProtocolState)」向下依次触达 「deltaNode.isTextual」、「deltaNode.asText」、「requiredText」；计算结果以「AgentStreamFrame」交给调用方。
    // 系统意义：「AgentNdjsonStreamClient.visibleDelta(JsonNode,long,String,ProtocolState)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private AgentStreamFrame visibleDelta(
            JsonNode node, long sequence, String nodeName, ProtocolState state) {
        if (nodeName == null) {
            throw new AgentStreamProtocolException(
                    "visible delta is missing node_name");
        }
        String fieldPath = requiredText(node, "field");
        if (!state.visibleFieldPaths.contains(fieldPath)) {
            throw new AgentStreamProtocolException(
                    "agent stream attempted to expose a non-public field");
        }
        JsonNode deltaNode = node.get("delta");
        if (deltaNode == null || !deltaNode.isTextual() || deltaNode.asText().isEmpty()) {
            throw new AgentStreamProtocolException(
                    "visible delta is missing delta text");
        }
        String delta = deltaNode.asText();
        if (delta.length() > MAX_DELTA_CHARS) {
            throw new AgentStreamProtocolException("visible delta exceeds size limit");
        }
        state.visibleCharacters += delta.length();
        if (state.visibleCharacters > MAX_VISIBLE_CHARS) {
            throw new AgentStreamProtocolException("visible stream exceeds size limit");
        }
        return new AgentStreamFrame(
                sequence,
                "visible_delta",
                nodeName,
                fieldPath,
                delta,
                null,
                null,
                null,
                null,
                null);
    }

    // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.usage(JsonNode,long,String)」。
    // 具体功能：「AgentNdjsonStreamClient.usage(JsonNode,long,String)」：读取模型名称、token_usage 对象和非负 latency_ms，形成只用于计量与观测的 usage 帧，最终返回「AgentStreamFrame」。
    // 上游调用：「AgentNdjsonStreamClient.usage(JsonNode,long,String)」的上游调用点包括 「AgentNdjsonStreamClient.parse」。
    // 下游影响：「AgentNdjsonStreamClient.usage(JsonNode,long,String)」向下依次触达 「usage.isObject」、「usage.deepCopy」、「requiredText」、「node.path("latency_ms").asLong」；计算结果以「AgentStreamFrame」交给调用方。
    // 系统意义：「AgentNdjsonStreamClient.usage(JsonNode,long,String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private AgentStreamFrame usage(JsonNode node, long sequence, String nodeName) {
        if (nodeName == null) {
            throw new AgentStreamProtocolException("usage event is missing node_name");
        }
        JsonNode usage = node.get("token_usage");
        if (usage == null || !usage.isObject()) {
            throw new AgentStreamProtocolException(
                    "usage event is missing token_usage object");
        }
        String model = requiredText(node, "model");
        long latencyMs = node.path("latency_ms").asLong(-1);
        if (latencyMs < 0) {
            throw new AgentStreamProtocolException("usage event has invalid latency_ms");
        }
        return new AgentStreamFrame(
                sequence,
                "usage",
                nodeName,
                null,
                null,
                usage.deepCopy(),
                model,
                latencyMs,
                null,
                null);
    }

    // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.finalFrame(JsonNode,long,String)」。
    // 具体功能：「AgentNdjsonStreamClient.finalFrame(JsonNode,long,String)」：要求 response 为 JSON 对象并执行深拷贝，形成候选终态帧；其正式发布仍要等待整条 NDJSON 流校验完成，最终返回「AgentStreamFrame」。
    // 上游调用：「AgentNdjsonStreamClient.finalFrame(JsonNode,long,String)」的上游调用点包括 「AgentNdjsonStreamClient.parse」。
    // 下游影响：「AgentNdjsonStreamClient.finalFrame(JsonNode,long,String)」向下依次触达 「result.isObject」、「result.deepCopy」；计算结果以「AgentStreamFrame」交给调用方。
    // 系统意义：「AgentNdjsonStreamClient.finalFrame(JsonNode,long,String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private AgentStreamFrame finalFrame(JsonNode node, long sequence, String nodeName) {
        JsonNode result = node.get("response");
        if (result == null || !result.isObject()) {
            throw new AgentStreamProtocolException(
                    "final event is missing response object");
        }
        return new AgentStreamFrame(
                sequence,
                "final",
                nodeName,
                null,
                null,
                null,
                null,
                null,
                result.deepCopy(),
                null);
    }

    // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.errorFrame(JsonNode,long,String)」。
    // 具体功能：「AgentNdjsonStreamClient.errorFrame(JsonNode,long,String)」：校验错误码/消息长度以及 retryable、visible_output_emitted 布尔元数据，把 Python 失败转换为 Java 可恢复策略能够识别的 error 帧，最终返回「AgentStreamFrame」。
    // 上游调用：「AgentNdjsonStreamClient.errorFrame(JsonNode,long,String)」的上游调用点包括 「AgentNdjsonStreamClient.parse」。
    // 下游影响：「AgentNdjsonStreamClient.errorFrame(JsonNode,long,String)」向下依次触达 「node.has」、「requiredText」、「node.get("retryable").isBoolean」、「node.get("visible_output_emitted").isBoolean」；计算结果以「AgentStreamFrame」交给调用方。
    // 系统意义：「AgentNdjsonStreamClient.errorFrame(JsonNode,long,String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private AgentStreamFrame errorFrame(JsonNode node, long sequence, String nodeName) {
        String code = requiredText(node, "code");
        String message = requiredText(node, "message");
        if (code.length() > 128 || message.length() > 4096) {
            throw new AgentStreamProtocolException("agent stream error exceeds size limit");
        }
        if (!node.has("retryable") || !node.get("retryable").isBoolean()
                || !node.has("visible_output_emitted")
                || !node.get("visible_output_emitted").isBoolean()) {
            throw new AgentStreamProtocolException(
                    "error event has invalid retryability metadata");
        }
        boolean retryable = node.path("retryable").asBoolean(false);
        boolean visibleOutputEmitted =
                node.path("visible_output_emitted").asBoolean(false);
        return new AgentStreamFrame(
                sequence,
                "error",
                nodeName,
                null,
                null,
                null,
                null,
                null,
                null,
                new AgentStreamFrame.StreamError(
                        code, message, retryable, visibleOutputEmitted));
    }

    // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.requiredText(JsonNode,String)」。
    // 具体功能：「AgentNdjsonStreamClient.requiredText(JsonNode,String)」：读取指定 JSON 字段并要求其为非空文本；缺失或空白时立即抛协议异常，避免后续校验把空字符串当成合法标识，最终返回「String」。
    // 上游调用：「AgentNdjsonStreamClient.requiredText(JsonNode,String)」的上游调用点包括 「AgentNdjsonStreamClient.parse」、「AgentNdjsonStreamClient.visibleDelta」、「AgentNdjsonStreamClient.usage」、「AgentNdjsonStreamClient.errorFrame」。
    // 下游影响：「AgentNdjsonStreamClient.requiredText(JsonNode,String)」向下依次触达 「node.path(field).asText」；计算结果以「String」交给调用方。
    // 系统意义：「AgentNdjsonStreamClient.requiredText(JsonNode,String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new AgentStreamProtocolException(
                    "agent stream event is missing " + field);
        }
        return value;
    }

    // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.requireText(JsonNode,String,String)」。
    // 具体功能：「AgentNdjsonStreamClient.requireText(JsonNode,String,String)」：比较指定 JSON 文本字段与服务端期望常量，用于锁定 schema_version、run_id 和 operation，防止串流或协议版本混用，最终返回「void」。
    // 上游调用：「AgentNdjsonStreamClient.requireText(JsonNode,String,String)」的上游调用点包括 「AgentNdjsonStreamClient.parse」。
    // 下游影响：「AgentNdjsonStreamClient.requireText(JsonNode,String,String)」向下依次触达 「node.path(field).asText」。
    // 系统意义：「AgentNdjsonStreamClient.requireText(JsonNode,String,String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(node.path(field).asText())) {
            throw new AgentStreamProtocolException(
                    "agent stream event has invalid " + field);
        }
    }

    // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.nullableText(JsonNode)」。
    // 具体功能：「AgentNdjsonStreamClient.nullableText(JsonNode)」：把缺失、JSON null 或空白文本统一转换为 Java null，保留非空 node_name 等可选协议字段，最终返回「String」。
    // 上游调用：「AgentNdjsonStreamClient.nullableText(JsonNode)」的上游调用点包括 「AgentNdjsonStreamClient.parse」。
    // 下游影响：「AgentNdjsonStreamClient.nullableText(JsonNode)」向下依次触达 「node.isNull」、「node.asText」；计算结果以「String」交给调用方。
    // 系统意义：「AgentNdjsonStreamClient.nullableText(JsonNode)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static String nullableText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }

    // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.withTrailingSlash(String)」。
    // 具体功能：「AgentNdjsonStreamClient.withTrailingSlash(String)」：规范化 Python Agent base URL，确保后续 URI.resolve 不会把基础路径最后一段误当成文件名替换，最终返回「String」。
    // 上游调用：「AgentNdjsonStreamClient.withTrailingSlash(String)」的上游调用点包括 「AgentNdjsonStreamClient.AgentNdjsonStreamClient」。
    // 下游影响：「AgentNdjsonStreamClient.withTrailingSlash(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentNdjsonStreamClient.withTrailingSlash(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static String withTrailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.withoutLeadingSlash(String)」。
    // 具体功能：「AgentNdjsonStreamClient.withoutLeadingSlash(String)」：移除 endpoint 开头的斜杠，使 URI.resolve 在既有 Agent base path 下拼接相对地址，最终返回「String」。
    // 上游调用：「AgentNdjsonStreamClient.withoutLeadingSlash(String)」的上游调用点包括 「AgentNdjsonStreamClient.stream」。
    // 下游影响：「AgentNdjsonStreamClient.withoutLeadingSlash(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentNdjsonStreamClient.withoutLeadingSlash(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static String withoutLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.closeQuietly(InputStream)」。
    // 具体功能：「AgentNdjsonStreamClient.closeQuietly(InputStream)」：在 HTTP 非成功或回显 Run ID 不一致时关闭响应流；清理异常被忽略，因为主错误已经决定本次运行失败，最终返回「void」。
    // 上游调用：「AgentNdjsonStreamClient.closeQuietly(InputStream)」的上游调用点包括 「AgentNdjsonStreamClient.stream」。
    // 下游影响：「AgentNdjsonStreamClient.closeQuietly(InputStream)」向下依次触达 「input.close」。
    // 系统意义：「AgentNdjsonStreamClient.closeQuietly(InputStream)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static void closeQuietly(java.io.InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The response is already being discarded.
        }
    }

    // 所属模块：【Agent 流式运行 / 外部集成层】类型「ProtocolState」。
    // 类型职责：承载协议状态在当前业务模块中的规则与协作边界；本类型显式提供 「ProtocolState」、「acceptEnvelope」、「assertComplete」。
    // 协作关系：主要由 「AgentNdjsonStreamClient.parse」、「AgentNdjsonStreamClient.stream」 使用。
    // 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    private static final class ProtocolState {
        private final String runId;
        private final String protocolOperation;
        private final Set<String> visibleFieldPaths;
        private long lastSequence = -1;
        private int visibleCharacters;
        private boolean started;
        private boolean terminal;

        // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.ProtocolState.ProtocolState(String,String,Set)」。
        // 具体功能：「AgentNdjsonStreamClient.ProtocolState.ProtocolState(String,String,Set)」：使用 「runId」(String)、「protocolOperation」(String)、「visibleFieldPaths」(Set) 初始化「ProtocolState」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
        // 上游调用：「AgentNdjsonStreamClient.ProtocolState.ProtocolState(String,String,Set)」的上游创建点包括 「AgentNdjsonStreamClient.stream」。
        // 下游影响：「AgentNdjsonStreamClient.ProtocolState.ProtocolState(String,String,Set)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
        // 系统意义：「AgentNdjsonStreamClient.ProtocolState.ProtocolState(String,String,Set)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
        // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
        private ProtocolState(
                String runId,
                String protocolOperation,
                Set<String> visibleFieldPaths) {
            this.runId = runId;
            this.protocolOperation = protocolOperation;
            this.visibleFieldPaths = visibleFieldPaths;
        }

        // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.ProtocolState.acceptEnvelope(long,String)」。
        // 具体功能：「AgentNdjsonStreamClient.ProtocolState.acceptEnvelope(long,String)」：维护单次流的协议状态机：强制 sequence=0 的 start 首帧、禁止重复 start、禁止终态后继续发帧，并在 final/error 到达时封闭事件序列，最终返回「void」。
        // 上游调用：「AgentNdjsonStreamClient.ProtocolState.acceptEnvelope(long,String)」的上游调用点包括 「AgentNdjsonStreamClient.parse」。
        // 下游影响：「AgentNdjsonStreamClient.ProtocolState.acceptEnvelope(long,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
        // 系统意义：「AgentNdjsonStreamClient.ProtocolState.acceptEnvelope(long,String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
        private void acceptEnvelope(long sequence, String event) {
            if (terminal) {
                throw new AgentStreamProtocolException("event emitted after terminal event");
            }
            if (!started && !"start".equals(event)) {
                throw new AgentStreamProtocolException("agent stream must begin with start");
            }
            if (!started && sequence != 0) {
                throw new AgentStreamProtocolException(
                        "agent stream start sequence must be zero");
            }
            if (started && "start".equals(event)) {
                throw new AgentStreamProtocolException("agent stream emitted duplicate start");
            }
            if ("start".equals(event)) {
                started = true;
            }
            if ("final".equals(event) || "error".equals(event)) {
                terminal = true;
            }
            lastSequence = sequence;
        }

        // 所属模块：【Agent 流式运行 / 外部集成层】「AgentNdjsonStreamClient.ProtocolState.assertComplete()」。
        // 具体功能：「AgentNdjsonStreamClient.ProtocolState.assertComplete()」：在 HTTP 响应读完后确认流同时出现 start 和 final/error；缺少任一端点都按截断协议处理，最终返回「void」。
        // 上游调用：「AgentNdjsonStreamClient.ProtocolState.assertComplete()」只由「ProtocolState」内部流程使用，负责封装“协议完整性”这一步校验、映射或状态转换。
        // 下游影响：「AgentNdjsonStreamClient.ProtocolState.assertComplete()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
        // 系统意义：「AgentNdjsonStreamClient.ProtocolState.assertComplete()」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
        private void assertComplete() {
            if (!started || !terminal) {
                throw new AgentStreamProtocolException(
                        "agent stream ended without a terminal event");
            }
        }
    }

    public static final class V2ProtocolState {
        private final String runId;
        private final String attemptId;
        private final Audience audience;
        private final Set<String> legacyVisibleFieldPaths;
        private final Map<String, Set<String>> visibleFieldsByNode;
        private long lastSequence = -1;
        private int visibleCharacters;
        private boolean terminal;
        private String activeFrameId;
        private int activeFrameSequence;
        private String activeFrameType;
        private int nextDeltaIndex;
        private int nextFrameSequence = 1;

        public V2ProtocolState(
                String runId,
                String attemptId,
                Audience audience,
                Set<String> visibleFieldPaths) {
            this.runId = requireV2Identifier(runId, "run_id");
            this.attemptId = requireV2Identifier(attemptId, "attempt_id");
            this.audience = java.util.Objects.requireNonNull(audience);
            this.legacyVisibleFieldPaths = Set.copyOf(
                    java.util.Objects.requireNonNull(visibleFieldPaths, "visibleFieldPaths"));
            this.legacyVisibleFieldPaths.forEach(
                    field -> requireV2Identifier(field, "visible field"));
            this.visibleFieldsByNode = null;
        }

        public V2ProtocolState(
                String runId,
                String attemptId,
                Audience audience,
                Map<String, Set<String>> visibleFieldsByNode) {
            this.runId = requireV2Identifier(runId, "run_id");
            this.attemptId = requireV2Identifier(attemptId, "attempt_id");
            this.audience = java.util.Objects.requireNonNull(audience);
            java.util.Objects.requireNonNull(visibleFieldsByNode, "visibleFieldsByNode");
            this.visibleFieldsByNode = visibleFieldsByNode.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            entry -> requireV2Identifier(entry.getKey(), "visible node"),
                            entry -> Set.copyOf(java.util.Objects.requireNonNull(
                                            entry.getValue(), "visible fields"))
                                    .stream()
                                    .map(field -> requireV2Identifier(field, "visible field"))
                                    .collect(java.util.stream.Collectors.toUnmodifiableSet())));
            this.legacyVisibleFieldPaths = null;
        }

        private void accept(long sequence, StreamEventType eventType) {
            if (terminal
                    || lastSequence == Long.MAX_VALUE
                    || sequence != lastSequence + 1) {
                throw new AgentStreamProtocolException(
                        "agent stream v2 sequence or terminal state is invalid");
            }
            if (lastSequence < 0 && eventType != StreamEventType.ATTEMPT_STARTED) {
                throw new AgentStreamProtocolException(
                        "agent stream v2 must start with attempt_started");
            }
            if (lastSequence < 0 && sequence != 0) {
                throw new AgentStreamProtocolException(
                        "agent stream v2 attempt_started sequence must be zero");
            }
            lastSequence = sequence;
            terminal = eventType == StreamEventType.FINAL
                    || eventType == StreamEventType.ERROR
                    || eventType == StreamEventType.ATTEMPT_ABORTED;
        }

        public void assertComplete() {
            if (lastSequence < 0 || !terminal) {
                throw new AgentStreamProtocolException(
                        "agent stream v2 ended without a terminal event");
            }
        }

        public boolean allowsVisibleField(String node, String field) {
            if (visibleFieldsByNode == null) {
                return legacyVisibleFieldPaths.contains(field);
            }
            return visibleFieldsByNode.getOrDefault(node, Set.of()).contains(field);
        }

        private void startFrame(String frameId, int frameSequence, String frameType) {
            if (activeFrameId != null || frameSequence != nextFrameSequence) {
                throw new AgentStreamProtocolException(
                        "agent stream v3 frame order is invalid");
            }
            activeFrameId = frameId;
            activeFrameSequence = frameSequence;
            activeFrameType = frameType;
            nextDeltaIndex = 0;
        }

        private void acceptFrameText(
                String frameId, int frameSequence, int deltaIndex, int chars) {
            requireActiveFrame(frameId, frameSequence);
            if (deltaIndex != nextDeltaIndex) {
                throw new AgentStreamProtocolException(
                        "agent stream v3 delta index is not contiguous");
            }
            nextDeltaIndex++;
        }

        private void acceptFrameSnapshot(
                String frameId, int frameSequence, int deltaIndex, int chars) {
            requireActiveFrame(frameId, frameSequence);
            if (deltaIndex < nextDeltaIndex) {
                throw new AgentStreamProtocolException(
                        "agent stream v3 snapshot rewinds delta index");
            }
            nextDeltaIndex = deltaIndex;
        }

        private void commitFrame(String frameId, int frameSequence, int chars) {
            requireActiveFrame(frameId, frameSequence);
            if (activeFrameSequence != nextFrameSequence) {
                throw new AgentStreamProtocolException(
                        "agent stream v3 committed frame order is invalid");
            }
            activeFrameId = null;
            activeFrameType = null;
            nextDeltaIndex = 0;
            nextFrameSequence++;
        }

        private void interruptFrame(String frameId, int frameSequence, int chars) {
            requireActiveFrame(frameId, frameSequence);
            activeFrameId = null;
            activeFrameType = null;
            nextDeltaIndex = 0;
            nextFrameSequence++;
        }

        private void requireActiveFrame(String frameId, int frameSequence) {
            if (activeFrameId == null
                    || !activeFrameId.equals(frameId)
                    || activeFrameSequence != frameSequence) {
                throw new AgentStreamProtocolException(
                        "agent stream v3 frame identity does not match");
            }
        }
    }

    private static boolean validV2ResultReference(String value) {
        if (value.length() > 1024) {
            return false;
        }
        try {
            URI reference = URI.create(value);
            String scheme = reference.getScheme();
            return reference.isAbsolute()
                    && Set.of("s3", "minio", "urn").contains(scheme)
                    && reference.getRawSchemeSpecificPart() != null
                    && !reference.getRawSchemeSpecificPart().isBlank();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}

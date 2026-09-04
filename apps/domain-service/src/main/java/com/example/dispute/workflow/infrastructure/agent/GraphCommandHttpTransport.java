package com.example.dispute.workflow.infrastructure.agent;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Streaming HTTP boundary for governed graph commands. */
public interface GraphCommandHttpTransport {

    int MAXIMUM_REQUEST_BODY_BYTES = 65_536;
    int MAXIMUM_LINE_BYTES = 32 * 1024;
    int MAXIMUM_PARALLEL_LINE_BYTES = 1024 * 1024;
    int MAXIMUM_RESPONSE_BYTES = 4 * 1024 * 1024;

    /** Factory provenance. Arbitrary implementations receive only the unverified proof. */
    default GraphTransportSecurityProof transportProof() {
        return GraphTransportSecurityProof.unverified();
    }

    void stream(
            Request request,
            AgentRunCancellationToken cancellationToken,
            Listener listener);

    record Request(
            URI uri,
            Map<String, String> headers,
            byte[] body,
            Duration timeout,
            int maximumLineBytes,
            int maximumResponseBytes) {

        public Request {
            Objects.requireNonNull(uri, "uri");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            body = Objects.requireNonNull(body, "body").clone();
            timeout = Objects.requireNonNull(timeout, "timeout");
            String scheme = uri.getScheme();
            if (!uri.isAbsolute()
                    || uri.isOpaque()
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getFragment() != null
                    || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("graph command URI is invalid");
            }
            if (body.length == 0 || body.length > MAXIMUM_REQUEST_BODY_BYTES) {
                throw new IllegalArgumentException("graph command request body exceeds 64 KiB");
            }
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("graph command timeout must be positive");
            }
            if (maximumLineBytes < 1 || maximumLineBytes > MAXIMUM_PARALLEL_LINE_BYTES) {
                throw new IllegalArgumentException("maximumLineBytes is invalid");
            }
            if (maximumResponseBytes < maximumLineBytes
                    || maximumResponseBytes > MAXIMUM_RESPONSE_BYTES) {
                throw new IllegalArgumentException("maximumResponseBytes is invalid");
            }
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    record ResponseHead(int statusCode, URI uri, Map<String, List<String>> headers) {

        public ResponseHead {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("statusCode is invalid");
            }
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(headers, "headers");
            headers = headers.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry -> List.copyOf(entry.getValue())));
        }
    }

    interface Listener {

        void onResponse(ResponseHead response);

        void onLine(String line);
    }
}

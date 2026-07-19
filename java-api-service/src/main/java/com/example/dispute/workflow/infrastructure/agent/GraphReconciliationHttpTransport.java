package com.example.dispute.workflow.infrastructure.agent;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** HTTP exchange boundary whose production implementation is configured with the service identity. */
public interface GraphReconciliationHttpTransport {

    /** Factory provenance symmetric with the streaming command transport. */
    default GraphTransportSecurityProof transportProof() {
        return GraphTransportSecurityProof.unverified();
    }

    Response exchange(Request request, AgentRunCancellationToken cancellationToken);

    record Request(
            URI uri,
            Map<String, String> headers,
            byte[] body,
            Duration timeout,
            int maximumResponseBytes) {

        public Request {
            Objects.requireNonNull(uri, "uri");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            body = Objects.requireNonNull(body, "body").clone();
            timeout = Objects.requireNonNull(timeout, "timeout");
            if (!uri.isAbsolute() || uri.getFragment() != null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("reconciliation URI is invalid");
            }
            if (body.length == 0 || body.length > 65_536) {
                throw new IllegalArgumentException("reconciliation request body exceeds 64 KiB");
            }
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("reconciliation timeout must be positive");
            }
            if (maximumResponseBytes < 1 || maximumResponseBytes > 131_072) {
                throw new IllegalArgumentException("maximumResponseBytes is invalid");
            }
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    record Response(int statusCode, Map<String, List<String>> headers, byte[] body) {

        public Response {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("statusCode is invalid");
            }
            Objects.requireNonNull(headers, "headers");
            headers = headers.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry -> List.copyOf(entry.getValue())));
            body = Objects.requireNonNull(body, "body").clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}

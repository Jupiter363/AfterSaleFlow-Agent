package com.example.dispute.workflow.api.intake;

import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.PayloadLoadRequest;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.ProposalPutRequest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

/** Strict raw-byte decoder for the private Intake exchange endpoints. */
public final class IntakeExchangeRequestCodec {

    static final int LOAD_REQUEST_MAX_BYTES = 16 * 1024;
    static final int PUT_REQUEST_MAX_BYTES = 128 * 1024;

    private final ObjectMapper mapper;

    public IntakeExchangeRequestCodec(ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        this.mapper.getFactory().configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
        this.mapper.getFactory()
                .configure(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature(), true);
    }

    public PayloadLoadRequest decodeLoad(byte[] body) {
        return decode(body, LOAD_REQUEST_MAX_BYTES, PayloadLoadRequest.class);
    }

    public ProposalPutRequest decodePut(byte[] body) {
        return decode(body, PUT_REQUEST_MAX_BYTES, ProposalPutRequest.class);
    }

    private <T> T decode(byte[] body, int maximumBytes, Class<T> type) {
        if (body == null || body.length == 0 || body.length > maximumBytes) {
            throw new IllegalArgumentException("Intake exchange request exceeds its byte limit");
        }
        try {
            return mapper.readValue(body, type);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Intake exchange request is not strict JSON for " + type.getSimpleName(),
                    failure);
        }
    }
}

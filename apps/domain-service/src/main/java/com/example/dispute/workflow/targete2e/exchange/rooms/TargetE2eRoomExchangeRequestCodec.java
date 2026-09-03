package com.example.dispute.workflow.targete2e.exchange.rooms;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

/** Bounded duplicate-member rejecting raw JSON decoder for target room exchange calls. */
public final class TargetE2eRoomExchangeRequestCodec {
  private final ObjectMapper mapper;
  public TargetE2eRoomExchangeRequestCodec(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper).copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
    this.mapper.getFactory().configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
    this.mapper.getFactory().configure(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature(), true);
  }
  public TargetE2eRoomExchangeContract.LoadRequest decodeLoad(byte[] value) { return decode(value, 16384, TargetE2eRoomExchangeContract.LoadRequest.class); }
  public TargetE2eRoomExchangeContract.PutRequest decodePut(byte[] value) { return decode(value, 131072, TargetE2eRoomExchangeContract.PutRequest.class); }
  private <T> T decode(byte[] value, int max, Class<T> type) {
    if (value == null || value.length == 0 || value.length > max) throw new IllegalArgumentException("target E2E room exchange request exceeds its byte limit");
    try { return mapper.readValue(value, type); } catch (IOException e) { throw new IllegalArgumentException("target E2E room exchange request is invalid", e); }
  }
}

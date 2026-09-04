package com.example.dispute.workflow.runtime.exchange.rooms;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

/** Bounded duplicate-member rejecting raw JSON decoder for target room exchange calls. */
public final class ProductionRoomExchangeRequestCodec {
  private final ObjectMapper mapper;
  public ProductionRoomExchangeRequestCodec(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper).copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
    this.mapper.getFactory().configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
    this.mapper.getFactory().configure(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature(), true);
  }
  public ProductionRoomExchangeContract.LoadRequest decodeLoad(byte[] value) { return decode(value, 16384, ProductionRoomExchangeContract.LoadRequest.class); }
  public ProductionRoomExchangeContract.PutRequest decodePut(byte[] value) { return decode(value, 131072, ProductionRoomExchangeContract.PutRequest.class); }
  private <T> T decode(byte[] value, int max, Class<T> type) {
    if (value == null || value.length == 0 || value.length > max) throw new IllegalArgumentException("production runtime room exchange request exceeds its byte limit");
    try { return mapper.readValue(value, type); } catch (IOException e) { throw new IllegalArgumentException("production runtime room exchange request is invalid", e); }
  }
}

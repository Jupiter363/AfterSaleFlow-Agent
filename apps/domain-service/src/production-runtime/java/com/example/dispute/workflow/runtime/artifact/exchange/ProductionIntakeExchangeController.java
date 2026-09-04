package com.example.dispute.workflow.runtime.artifact.exchange;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.api.intake.IntakeExchangeRequestCodec;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.PayloadLoadResponse;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.ProposalPutResponse;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Target-profile HTTP boundary for the existing private Intake exchange protocol. */
@RestController
@Profile("production-runtime & api")
@ConditionalOnProperty(name = "app.production-runtime.enabled", havingValue = "true")
@RequestMapping("/internal/graph/intake/v2")
public final class ProductionIntakeExchangeController {

  private final IntakeExchangeService service;
  private final AppProperties properties;
  private final IntakeExchangeRequestCodec codec;

  public ProductionIntakeExchangeController(
      IntakeExchangeService service, AppProperties properties, ObjectMapper objectMapper) {
    this.service = Objects.requireNonNull(service, "service");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.codec = new IntakeExchangeRequestCodec(Objects.requireNonNull(objectMapper, "objectMapper"));
  }

  @PostMapping(
      path = "/payload:load",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public PayloadLoadResponse load(
      @RequestHeader(value = "X-Service-Secret", required = false) String serviceSecret,
      @RequestBody byte[] body) {
    requireServiceSecret(serviceSecret);
    return service.load(codec.decodeLoad(body));
  }

  @PostMapping(
      path = "/proposals:put",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ProposalPutResponse put(
      @RequestHeader(value = "X-Service-Secret", required = false) String serviceSecret,
      @RequestBody byte[] body) {
    requireServiceSecret(serviceSecret);
    return service.put(codec.decodePut(body));
  }

  private void requireServiceSecret(String supplied) {
    byte[] expected = properties.security().serviceSecret().getBytes(StandardCharsets.UTF_8);
    byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, actual)) {
      throw new ForbiddenException("invalid Java service credential");
    }
  }
}

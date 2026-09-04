package com.example.dispute.workflow.runtime.artifact.exchange.rooms;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeRequestCodec;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeService;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeContract.LoadResponse;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeContract.PutResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** Internal-only HTTP facade for the target non-Intake immutable object exchange. */
@RestController @Profile("production-runtime & api")
@ConditionalOnProperty(name="app.production-runtime.enabled", havingValue="true")
@RequestMapping("/internal/graph/production-runtime/rooms")
public final class ProductionRoomExchangeController {
  private final ProductionRoomExchangeService service; private final AppProperties properties; private final ProductionRoomExchangeRequestCodec codec;
  public ProductionRoomExchangeController(ProductionRoomExchangeService service, AppProperties properties, ObjectMapper mapper) { this.service=service; this.properties=properties; this.codec=new ProductionRoomExchangeRequestCodec(mapper); }
  @PostMapping(path="/object:load", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
  public LoadResponse load(@RequestHeader(value="X-Service-Secret",required=false) String secret,@RequestBody byte[] body) { auth(secret); return service.load(codec.decodeLoad(body)); }
  @PostMapping(path="/proposal:put", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
  public PutResponse put(@RequestHeader(value="X-Service-Secret",required=false) String secret,@RequestBody byte[] body) { auth(secret); return service.put(codec.decodePut(body)); }
  private void auth(String supplied) { byte[] expected=properties.security().serviceSecret().getBytes(StandardCharsets.UTF_8); byte[] actual=supplied==null?new byte[0]:supplied.getBytes(StandardCharsets.UTF_8); if(!MessageDigest.isEqual(expected,actual)) throw new ForbiddenException("invalid Java service credential"); }
}

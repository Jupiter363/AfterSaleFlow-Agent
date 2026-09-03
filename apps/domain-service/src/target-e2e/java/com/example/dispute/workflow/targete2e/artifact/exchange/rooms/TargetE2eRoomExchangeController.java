package com.example.dispute.workflow.targete2e.artifact.exchange.rooms;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeRequestCodec;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeService;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.LoadResponse;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.PutResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** Internal-only HTTP facade for the target non-Intake immutable object exchange. */
@RestController @Profile("target-e2e & api")
@ConditionalOnProperty(name="app.target-e2e.enabled", havingValue="true")
@RequestMapping("/internal/graph/target-e2e/rooms")
public final class TargetE2eRoomExchangeController {
  private final TargetE2eRoomExchangeService service; private final AppProperties properties; private final TargetE2eRoomExchangeRequestCodec codec;
  public TargetE2eRoomExchangeController(TargetE2eRoomExchangeService service, AppProperties properties, ObjectMapper mapper) { this.service=service; this.properties=properties; this.codec=new TargetE2eRoomExchangeRequestCodec(mapper); }
  @PostMapping(path="/object:load", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
  public LoadResponse load(@RequestHeader(value="X-Service-Secret",required=false) String secret,@RequestBody byte[] body) { auth(secret); return service.load(codec.decodeLoad(body)); }
  @PostMapping(path="/proposal:put", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
  public PutResponse put(@RequestHeader(value="X-Service-Secret",required=false) String secret,@RequestBody byte[] body) { auth(secret); return service.put(codec.decodePut(body)); }
  private void auth(String supplied) { byte[] expected=properties.security().serviceSecret().getBytes(StandardCharsets.UTF_8); byte[] actual=supplied==null?new byte[0]:supplied.getBytes(StandardCharsets.UTF_8); if(!MessageDigest.isEqual(expected,actual)) throw new ForbiddenException("invalid Java service credential"); }
}

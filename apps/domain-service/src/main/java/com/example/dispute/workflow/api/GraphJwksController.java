package com.example.dispute.workflow.api;

import com.example.dispute.workflow.infrastructure.security.GraphJwkSetProvider;
import com.example.dispute.workflow.infrastructure.security.GraphJwkSetProvider.JwkSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Verification-only JWKS endpoint used by the Python Graph security runtime. */
@RestController
@ConditionalOnProperty(
        name = "app.graph-jwks.enabled",
        havingValue = "true")
public class GraphJwksController {

    public static final String PATH = "/.well-known/graph-jwks.json";

    private final GraphJwkSetProvider keySetProvider;

    public GraphJwksController(GraphJwkSetProvider keySetProvider) {
        this.keySetProvider = keySetProvider;
    }

    @GetMapping(path = PATH, produces = "application/json")
    public ResponseEntity<JwkSet> keys() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(keySetProvider.jwkSet());
    }
}

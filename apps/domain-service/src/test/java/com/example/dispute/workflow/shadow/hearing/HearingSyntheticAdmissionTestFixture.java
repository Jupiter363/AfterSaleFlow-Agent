package com.example.dispute.workflow.shadow.hearing;

import com.example.dispute.workflow.config.HearingEpochSelectionProperties;
import com.example.dispute.workflow.config.HearingEpochSelector;
import com.example.dispute.workflow.config.HearingEpochSelector.EpochAdmission;
import com.example.dispute.workflow.config.HearingEpochSelector.ShadowAuthorization;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.shadow.hearing.HearingSignedSyntheticAdmissionService.AdmissionContext;
import com.example.dispute.workflow.shadow.hearing.HearingSyntheticAdmissionClaims.ScopeKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

final class HearingSyntheticAdmissionTestFixture {

    static final Instant NOW = Instant.parse("2026-07-24T00:00:30Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String HASH = "a".repeat(64);
    static final String FIXTURE = "synthetic-fixture-1";
    static final String TENANT = "synthetic-tenant-1";
    static final String CASE = "synthetic-case-1";
    static final long EPOCH = 7;
    static final String SCOPE = "actor-user-1";
    static final String KEY_ID = "hearing-key-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final KeyPair keyPair = keyPair();
    private final HearingEpochSelector selector = new HearingEpochSelector(
            new HearingEpochSelectionProperties(
                    WriterMode.SHADOW, 10_000, "hearing.synthetic.v1", true));

    HearingSyntheticAdmissionTrustSet trustSet() {
        return new HearingSyntheticAdmissionTrustSet(
                Map.of(KEY_ID, (ECPublicKey) keyPair.getPublic()));
    }

    HearingEpochSelector selector() {
        return selector;
    }

    AdmissionContext context() {
        return new AdmissionContext(FIXTURE, TENANT, CASE, EPOCH, ScopeKind.ACTOR, SCOPE, HASH);
    }

    ObjectNode claims() {
        String selectionHash = selector.decide(
                        RoomType.HEARING,
                        TENANT,
                        CASE,
                        EpochAdmission.PINNED_NEW_EPOCH,
                        ShadowAuthorization.JAVA_SIGNED_SYNTHETIC_FIXTURE)
                .cohortKeyHash();
        ObjectNode claims = JSON.createObjectNode();
        claims.put("schema_version", HearingSyntheticAdmissionClaims.SCHEMA_VERSION);
        claims.put("iss", HearingSyntheticAdmissionClaims.ISSUER);
        claims.put("aud", HearingSyntheticAdmissionClaims.AUDIENCE);
        claims.put("sub", HearingSyntheticAdmissionClaims.SUBJECT);
        claims.put("jti", "synthetic-token-1");
        claims.put("iat", NOW.minusSeconds(10).getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(10).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(40).getEpochSecond());
        claims.put("room_type", "HEARING");
        claims.put("writer_mode", "SHADOW");
        claims.put("fixture_id", FIXTURE);
        claims.put("tenant_surrogate", TENANT);
        claims.put("case_id", CASE);
        claims.put("room_epoch", EPOCH);
        claims.put("epoch_admission", "PINNED_NEW_EPOCH");
        claims.put("scope_kind", "ACTOR");
        claims.put("scope_id", SCOPE);
        claims.put(
                "scope_hash",
                HearingSyntheticAdmissionClaims.calculateScopeHash(
                        FIXTURE, TENANT, CASE, EPOCH, ScopeKind.ACTOR, SCOPE));
        claims.put("selection_hash", selectionHash);
        claims.put("expected_trace_hash", HASH);
        ObjectNode labels = claims.putObject("labels");
        labels.put("suite", "phase6");
        labels.put("scenario", "happy_path");
        labels.put("result_class", "match");
        return claims;
    }

    String sign(ObjectNode claims) {
        try {
            ObjectNode header = JSON.createObjectNode();
            header.put("alg", "ES256");
            header.put("typ", Es256HearingSyntheticAdmissionVerifier.TOKEN_TYPE);
            header.put("kid", KEY_ID);
            String headerSegment = ENCODER.encodeToString(JSON.writeValueAsBytes(header));
            String claimsSegment = ENCODER.encodeToString(JSON.writeValueAsBytes(claims));
            String signingInput = headerSegment + "." + claimsSegment;
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(keyPair.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + ENCODER.encodeToString(signature.sign());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

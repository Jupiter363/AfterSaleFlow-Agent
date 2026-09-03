package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.AdmissionAttempt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;

final class IntakeSyntheticAdmissionTestFixture {

    static final Instant NOW = Instant.parse("2026-07-22T07:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String KEY_ID = "intake-admission-test.v1";
    static final String TENANT = "tenant-p4-admission";
    static final String CASE_ID = "CASE_P4_ADMISSION";
    static final String COMMAND_ID = "CMD_P4_ADMISSION";
    static final String THREAD_ID = "grt.v1." + "c".repeat(32);
    static final String AGENT_SESSION_ID = "AGENT_SESSION_P4_ADMISSION";
    static final String ACTOR_SCOPE_HASH = "a".repeat(64);
    static final String PAYLOAD_HASH = "b".repeat(64);
    static final String REQUEST_HASH = "d".repeat(64);
    static final String SELECTION_HASH = "e".repeat(64);
    static final String REGISTRATION_HASH = "f".repeat(64);
    static final String PARITY_HASH = "1".repeat(64);
    static final String PAYLOAD_REF = "urn:after-sale-flow:intake-command:" + COMMAND_ID;
    static final String OPERATION_KEY = "intake.operation:" + CASE_ID + ":" + COMMAND_ID;
    static final long DEADLINE_MILLIS = NOW.plusSeconds(300).toEpochMilli();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final KeyPair keyPair;

    IntakeSyntheticAdmissionTestFixture() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            keyPair = generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    ECPublicKey publicKey() {
        return (ECPublicKey) keyPair.getPublic();
    }

    RetryBudget retryBudget() {
        return new RetryBudget("intake-retry-budget.v1", 2, 3, 1);
    }

    IntakeWorkflowCommand command() {
        return new IntakeWorkflowCommand(
                "intake-workflow-command.v1",
                COMMAND_ID,
                TENANT,
                CASE_ID,
                9,
                41,
                1,
                IntakeCommandType.INTAKE_MESSAGE,
                IntakeParty.INITIATOR,
                ACTOR_SCOPE_HASH,
                PAYLOAD_REF,
                PAYLOAD_HASH,
                OPERATION_KEY,
                REQUEST_HASH);
    }

    ObjectNode header() {
        ObjectNode header = JSON.createObjectNode();
        header.put("alg", "ES256");
        header.put("typ", "intake-synthetic-admission+jwt");
        header.put("kid", KEY_ID);
        return header;
    }

    ObjectNode claims() {
        ObjectNode claims = JSON.createObjectNode();
        claims.put("schema_version", "intake-synthetic-admission-claims.v1");
        claims.put("iss", "after-sale-flow.synthetic-driver");
        claims.put("aud", "after-sale-flow.java-intake-admission");
        claims.put("sub", "signed-synthetic-intake-shadow");
        claims.put("jti", "JTI_P4_ADMISSION_1");
        claims.put("iat", NOW.getEpochSecond());
        claims.put("nbf", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(60).getEpochSecond());
        claims.put("room_type", "INTAKE");
        claims.put("writer_mode", "SHADOW");
        claims.put("tenant_surrogate", TENANT);
        claims.put("case_id", CASE_ID);
        claims.put("room_epoch", 9);
        claims.put("fencing_token", 41);
        claims.put("command_id", COMMAND_ID);
        claims.put("command_sequence", 1);
        claims.put("command_type", "INTAKE_MESSAGE");
        claims.put("party", "INITIATOR");
        claims.put("payload_ref", PAYLOAD_REF);
        claims.put("payload_hash", PAYLOAD_HASH);
        claims.put("command_operation_key", OPERATION_KEY);
        claims.put("process_revision", 0);
        claims.put("room_revision", 0);
        claims.put("actor_scope_hash", ACTOR_SCOPE_HASH);
        claims.put("request_hash", REQUEST_HASH);
        claims.put("thread_id", THREAD_ID);
        claims.put("agent_session_id", AGENT_SESSION_ID);
        claims.put("deadline_epoch_millis", DEADLINE_MILLIS);
        ObjectNode retry = claims.putObject("retry_budget");
        retry.put("schema_version", "intake-retry-budget.v1");
        retry.put("provider_attempts_remaining", 2);
        retry.put("activity_attempts_remaining", 3);
        retry.put("repairs_remaining", 1);
        claims.put("logical_run_id", "RUN_P4_ADMISSION_1");
        claims.put("attempt_id", "ATTEMPT_P4_ADMISSION_1");
        claims.put("selection_hash", SELECTION_HASH);
        claims.put("registration_hash", REGISTRATION_HASH);
        ObjectNode pins = claims.putObject("pins");
        pins.put("case_workflow_type", "CaseProcessWorkflow");
        pins.put("case_workflow_build_id", "synthetic-case-build");
        pins.put("room_workflow_type", "IntakeRoomWorkflow");
        pins.put("room_workflow_build_id", "synthetic-room-build");
        pins.put("process_contract_version", "case-process-contract.v1");
        pins.put("graph_key", "intake.v2");
        pins.put("graph_version", "2.0.0");
        pins.put("checkpoint_schema_version", "intake-checkpoint.v2");
        pins.put("state_schema_version", "intake-graph-state.v2");
        pins.put("stream_protocol", "agent-stream.v2");
        pins.put("prompt_version", "intake-prompt.v2");
        pins.put("model_profile_id", "intake-model.synthetic.v1");
        pins.put("output_schema_version", "intake-turn-proposal.v2");
        pins.put("policy_version", "intake-policy.v2");
        pins.put("guardrail_version", "intake-guardrail.v2");
        pins.put("tool_policy_version", "no-tools.v1");
        pins.put("cohort_policy_version", "synthetic-cohort.v1");
        pins.put("agent_key", "DISPUTE_INTAKE_OFFICER");
        pins.put("agent_session_profile_version", "agent-session-profile.v1");
        pins.put("memory_policy_id", "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1");
        claims.put("parity_baseline_ref", "urn:after-sale-flow:intake-parity:baseline-v1");
        claims.put("parity_baseline_hash", PARITY_HASH);
        return claims;
    }

    String sign(ObjectNode claims) {
        return sign(header(), claims);
    }

    String sign(ObjectNode header, ObjectNode claims) {
        try {
            String encodedHeader = BASE64_URL.encodeToString(JSON.writeValueAsBytes(header));
            String encodedClaims = BASE64_URL.encodeToString(JSON.writeValueAsBytes(claims));
            String signingInput = encodedHeader + "." + encodedClaims;
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(keyPair.getPrivate());
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + BASE64_URL.encodeToString(signer.sign());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    AdmissionAttempt attempt(String compactJws) {
        return new AdmissionAttempt(
                "intake-signed-synthetic-admission-attempt.v1",
                TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC,
                KEY_ID,
                compactJws,
                sha256(compactJws),
                THREAD_ID,
                AGENT_SESSION_ID,
                DEADLINE_MILLIS,
                retryBudget());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

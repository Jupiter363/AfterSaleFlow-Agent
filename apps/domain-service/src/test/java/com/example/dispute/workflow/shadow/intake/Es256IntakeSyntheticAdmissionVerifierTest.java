package com.example.dispute.workflow.shadow.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.shadow.intake.admission.Es256IntakeSyntheticAdmissionVerifier;
import com.example.dispute.workflow.shadow.intake.admission.IntakeSyntheticAdmissionException;
import com.example.dispute.workflow.shadow.intake.admission.IntakeSyntheticAdmissionTrustSet;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Es256IntakeSyntheticAdmissionVerifierTest {

    @Test
    void verifiesARealP256SignatureAndEveryExactClaim() {
        IntakeSyntheticAdmissionTestFixture fixture = new IntakeSyntheticAdmissionTestFixture();
        var verifier = verifier(fixture);
        String compactJws = fixture.sign(fixture.claims());

        var verified = verifier.verify(fixture.attempt(compactJws), fixture.command());

        assertThat(verified.keyId()).isEqualTo(IntakeSyntheticAdmissionTestFixture.KEY_ID);
        assertThat(verified.envelopeHash()).matches("[0-9a-f]{64}");
        assertThat(verified.authorizationHash()).matches("[0-9a-f]{64}");
        assertThat(verified.claims().commandOperationKey())
                .isEqualTo(IntakeSyntheticAdmissionTestFixture.OPERATION_KEY);
        assertThat(verified.claims().activityPins().workflowBuildId())
                .isEqualTo("synthetic-room-build");
    }

    @Test
    void rejectsUnknownHeaderOrClaimFieldsAndNoncanonicalHashes() {
        IntakeSyntheticAdmissionTestFixture fixture = new IntakeSyntheticAdmissionTestFixture();
        var verifier = verifier(fixture);

        ObjectNode extraHeader = fixture.header().put("crit", "kid");
        assertRejected(verifier, fixture, fixture.sign(extraHeader, fixture.claims()), "ADMISSION_FIELDS_INVALID");

        ObjectNode extraClaim = fixture.claims().put("formal_authority", true);
        assertRejected(verifier, fixture, fixture.sign(extraClaim), "ADMISSION_FIELDS_INVALID");

        ObjectNode uppercaseHash = fixture.claims().put("request_hash", "D".repeat(64));
        assertRejected(verifier, fixture, fixture.sign(uppercaseHash), "ADMISSION_CLAIMS_INVALID");

        ObjectNode toolEnabled = fixture.claims();
        ((ObjectNode) toolEnabled.get("pins")).put("tool_policy_version", "tools-enabled.v1");
        assertRejected(verifier, fixture, fixture.sign(toolEnabled), "ADMISSION_CLAIMS_INVALID");
    }

    @Test
    void rejectsWrongSignatureKeyIdentityAndValidityWindow() throws Exception {
        IntakeSyntheticAdmissionTestFixture fixture = new IntakeSyntheticAdmissionTestFixture();
        var verifier = verifier(fixture);

        String compactJws = fixture.sign(fixture.claims());
        String damaged = withDamagedSignature(compactJws);
        assertRejected(verifier, fixture, damaged, "ADMISSION_SIGNATURE_INVALID");

        ObjectNode wrongKid = fixture.header().put("kid", "not-allowlisted.v1");
        assertRejected(verifier, fixture, fixture.sign(wrongKid, fixture.claims()), "ADMISSION_KEY_REJECTED");

        ObjectNode longWindow = fixture.claims()
                .put("exp", IntakeSyntheticAdmissionTestFixture.NOW.plusSeconds(61).getEpochSecond());
        assertRejected(verifier, fixture, fixture.sign(longWindow), "ADMISSION_CLAIMS_INVALID");

        KeyPairGenerator p384 = KeyPairGenerator.getInstance("EC");
        p384.initialize(new ECGenParameterSpec("secp384r1"));
        ECPublicKey wrongCurve = (ECPublicKey) p384.generateKeyPair().getPublic();
        assertThatThrownBy(() -> new IntakeSyntheticAdmissionTrustSet(Map.of("p384.v1", wrongCurve)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("P-256");
    }

    private static Es256IntakeSyntheticAdmissionVerifier verifier(
            IntakeSyntheticAdmissionTestFixture fixture) {
        return new Es256IntakeSyntheticAdmissionVerifier(
                new IntakeSyntheticAdmissionTrustSet(Map.of(
                        IntakeSyntheticAdmissionTestFixture.KEY_ID, fixture.publicKey())),
                IntakeSyntheticAdmissionTestFixture.CLOCK);
    }

    private static void assertRejected(
            Es256IntakeSyntheticAdmissionVerifier verifier,
            IntakeSyntheticAdmissionTestFixture fixture,
            String compactJws,
            String code) {
        assertThatThrownBy(() -> verifier.verify(fixture.attempt(compactJws), fixture.command()))
                .isInstanceOfSatisfying(
                        IntakeSyntheticAdmissionException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static String withDamagedSignature(String compactJws) {
        String[] segments = compactJws.split("\\.", -1);
        byte[] signature = Base64.getUrlDecoder().decode(segments[2]);
        signature[0] ^= 1;
        return segments[0] + "." + segments[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }
}

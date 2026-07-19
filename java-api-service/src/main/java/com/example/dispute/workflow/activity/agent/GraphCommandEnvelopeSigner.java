package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/** Issues one short-lived execution credential for an immutable Graph command. */
public interface GraphCommandEnvelopeSigner {

    SignedEnvelope sign(
            RoomGraphCommand command,
            GraphRegistryBindingPolicy.ExpectedBinding expectedRegistryBinding);

    record SignedEnvelope(
            String compactJws,
            String keyId,
            String jti,
            Instant issuedAt,
            Instant expiresAt) {

        private static final int MAXIMUM_COMPACT_JWS_CHARACTERS = 8_192;
        private static final Pattern BASE64_URL_SEGMENT =
                Pattern.compile("[A-Za-z0-9_-]+");
        private static final Pattern BOUNDED_IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        public SignedEnvelope {
            compactJws = requireText(compactJws, "compactJws");
            keyId = requireText(keyId, "keyId");
            jti = requireText(jti, "jti");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!isWellFormedCompactJws(compactJws)) {
                throw new IllegalArgumentException(
                        "compactJws must be a bounded ES256 compact JWS");
            }
            if (!expiresAt.isAfter(issuedAt)
                    || expiresAt.isAfter(issuedAt.plusSeconds(60))) {
                throw new IllegalArgumentException("command credential lifetime is invalid");
            }
        }

        public static boolean isWellFormedCompactJws(String value) {
            if (value == null || value.length() > MAXIMUM_COMPACT_JWS_CHARACTERS) {
                return false;
            }
            String[] segments = value.split("\\.", -1);
            if (segments.length != 3) {
                return false;
            }

            Base64.Decoder decoder = Base64.getUrlDecoder();
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            for (int index = 0; index < segments.length; index++) {
                String segment = segments[index];
                if (!BASE64_URL_SEGMENT.matcher(segment).matches()) {
                    return false;
                }
                try {
                    byte[] decoded = decoder.decode(segment);
                    if (decoded.length == 0
                            || !segment.equals(encoder.encodeToString(decoded))
                            || (index == 2 && decoded.length != 64)) {
                        return false;
                    }
                } catch (IllegalArgumentException invalidBase64Url) {
                    return false;
                }
            }
            return true;
        }

        public static boolean isBoundedIdentifier(String value) {
            return value != null && BOUNDED_IDENTIFIER.matcher(value).matches();
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value;
        }
    }
}

package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.room.application.IntakeCaseSeedMetadata;
import com.example.dispute.room.application.IntakeLobbySeed;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class IntakeCaseSeedMetadataTest {

    @Test
    void roundTripsTrustedInitialFactsWithoutPersistingSubjectiveRespondentInput() {
        IntakeLobbySeed seed = new IntakeLobbySeed(
                "ORDER_1",
                "AFTER_1",
                "LOG_1",
                "USER",
                "商品频繁自动关机",
                "REPLACE_OR_REPAIR",
                new IntakeLobbySeed.ClaimResolutionSeed(
                        "USER",
                        "REPLACE_OR_REPAIR",
                        new BigDecimal("299.00"),
                        "智能设备 1 件",
                        "远程排障和恢复出厂设置均未解决。",
                        "希望换货或维修"),
                new IntakeLobbySeed.RespondentAttitudeSeed(
                        "MERCHANT", "NOT_RESPONDED", "尚未回应", "IMPORT", 0.5));

        String encoded = IntakeCaseSeedMetadata.encode(seed, "EXTERNAL_IMPORT");
        var decoded = IntakeCaseSeedMetadata.decode(encoded)
                .orElseThrow();

        assertThat(decoded.formSource()).isEqualTo("EXTERNAL_IMPORT");
        assertThat(decoded.requestedOutcomeHint()).isEqualTo("REPLACE_OR_REPAIR");
        assertThat(decoded.claimResolutionSeed().requestedResolution())
                .isEqualTo("REPLACE_OR_REPAIR");
        assertThat(decoded.claimResolutionSeed().requestedAmount())
                .isEqualByComparingTo("299.00");
        assertThat(decoded.claimResolutionSeed().originalStatement()).isNull();
        assertThat(decoded.respondentAttitudeSeed()).isNull();

        String rebound = IntakeCaseSeedMetadata.bind(
                "\n  " + encoded + "  \n", seed, "EXTERNAL_IMPORT");
        assertThat(IntakeCaseSeedMetadata.decode(rebound)).contains(decoded);

        IntakeLobbySeed conflicting = new IntakeLobbySeed(
                "ORDER_1",
                "AFTER_1",
                "LOG_1",
                "USER",
                "商品频繁自动关机",
                "REFUND");
        assertThatThrownBy(() ->
                        IntakeCaseSeedMetadata.bind(encoded, conflicting, "EXTERNAL_IMPORT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void rejectsMalformedPersistedSeedMetadata() {
        assertThatThrownBy(() -> IntakeCaseSeedMetadata.decode(
                        "{\"schema_version\":\"wrong\",\"intake_initial_case_facts\":{}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Intake seed metadata");
    }
}

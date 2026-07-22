package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IntakeSyntheticAdmissionTrustPropertiesTest {

    @Test
    void keepsVerificationTrustDisabledUntilAnAbsolutePublicMountIsExplicit() {
        IntakeSyntheticAdmissionTrustProperties disabled =
                new IntakeSyntheticAdmissionTrustProperties(false, null);
        assertThatThrownBy(disabled::requireConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
        assertThatThrownBy(() -> new IntakeSyntheticAdmissionTrustProperties(
                        true, Path.of("relative-trust")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");

        Path absolute = Path.of("C:/synthetic-admission-public-trust").toAbsolutePath();
        assertThat(new IntakeSyntheticAdmissionTrustProperties(true, absolute).requireConfigured())
                .isEqualTo(absolute);
    }
}

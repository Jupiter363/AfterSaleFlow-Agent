package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.shadow.hearing.Es256HearingSyntheticAdmissionVerifier;
import com.example.dispute.workflow.shadow.hearing.HearingNoFormalSinkGuard;
import com.example.dispute.workflow.shadow.hearing.HearingSignedSyntheticAdmissionService;
import com.example.dispute.workflow.shadow.hearing.HearingSyntheticAdmissionTrustSet;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HearingSyntheticShadowConfigurationTest {

    private static final String[] ENABLED = {
        "app.orchestration.hearing-epoch-selection.mode=SHADOW",
        "app.orchestration.hearing-epoch-selection.synthetic-shadow-cohort-basis-points=10000",
        "app.orchestration.hearing-epoch-selection.cohort-policy-version=hearing.synthetic.v1",
        "app.orchestration.hearing-epoch-selection.signed-synthetic-shadow-enabled=true"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HearingSyntheticShadowConfiguration.class);

    @Test
    void defaultAssemblyHasSelectorAndGuardButNoSyntheticExecution() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(HearingEpochSelector.class);
            assertThat(context).hasSingleBean(HearingNoFormalSinkGuard.class);
            assertThat(context).doesNotHaveBean(Es256HearingSyntheticAdmissionVerifier.class);
            assertThat(context).doesNotHaveBean(HearingSignedSyntheticAdmissionService.class);
        });
    }

    @Test
    void enabledAssemblyRequiresExplicitVerificationTrust() {
        runner.withPropertyValues(ENABLED).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("exactly one Hearing synthetic admission trust set");
        });
    }

    @Test
    void signedSyntheticAssemblyExposesNoFormalFinalizerOrRealCaseResolver() {
        runner.withPropertyValues(ENABLED)
                .withBean(HearingSyntheticAdmissionTrustSet.class, this::trustSet)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Es256HearingSyntheticAdmissionVerifier.class);
                    assertThat(context).hasSingleBean(HearingSignedSyntheticAdmissionService.class);
                    assertThat(Arrays.stream(context.getBeanDefinitionNames())
                                    .map(String::toLowerCase)
                                    .filter(name -> name.contains("hearing"))
                                    .filter(name -> name.contains("finalizer")
                                            || name.contains("resolver")
                                            || name.contains("temporal")))
                            .isEmpty();
                    assertThat(context.getBean(HearingNoFormalSinkGuard.class)
                                    .verify(HearingNoFormalSinkGuard.AssemblyContract.signedSynthetic())
                                    .sinkDisposition())
                            .isEqualTo(HearingNoFormalSinkGuard.SinkDisposition.NO_FORMAL_SINK);
                });
    }

    @Test
    void enablementWithLegacyOrPartialModeFailsClosed() {
        runner.withPropertyValues(
                        "app.orchestration.hearing-epoch-selection.mode=LEGACY",
                        "app.orchestration.hearing-epoch-selection.signed-synthetic-shadow-enabled=true")
                .withBean(HearingSyntheticAdmissionTrustSet.class, this::trustSet)
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("requires mode, cohort, policy, and enablement locks"));
    }

    private HearingSyntheticAdmissionTrustSet trustSet() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return new HearingSyntheticAdmissionTrustSet(
                    Map.of("hearing-key-1", (ECPublicKey) generator.generateKeyPair().getPublic()));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

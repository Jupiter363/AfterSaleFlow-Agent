package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportSecurityProof;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GraphTransportConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GraphTransportConfiguration.class)
            .withPropertyValues(
                    "app.agent-run-v2.graph-client.mode=SHADOW",
                    "app.agent-run-v2.graph-client.request-timeout=PT10M");

    @Test
    void plaintextRequiresBothTheExplicitFlagAndALocalOrTestProfile() {
        ApplicationContextRunner plaintext = runner.withPropertyValues(
                "app.agent-run-v2.graph-client.base-uri=http://127.0.0.1:18000",
                "app.agent-run-v2.graph-client.allow-plaintext-transport=true");

        plaintext.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("local or test profile");
        });
        plaintext.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GraphTransportBundle bundle = context.getBean(GraphTransportBundle.class);
                    assertThat(bundle.transportProof().mode())
                            .isEqualTo(GraphTransportSecurityProof.Mode.LOCAL_PLAINTEXT);
                    assertThat(bundle.commandTransport().transportProof())
                            .isSameAs(bundle.reconciliationTransport().transportProof())
                            .isSameAs(bundle.transportProof());
                });
    }

    @Test
    void httpsFailsClosedWithoutCompleteClientIdentityAndTrustMaterial() {
        runner.withPropertyValues(
                        "app.agent-run-v2.graph-client.base-uri=https://python-agent-service:8000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("complete PKCS12");
                });
    }

    @Test
    void disabledModeCreatesNoTransportBundle() {
        new ApplicationContextRunner()
                .withUserConfiguration(GraphTransportConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(GraphTransportBundle.class);
                });
    }
}

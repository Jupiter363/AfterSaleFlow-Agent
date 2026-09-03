package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.api.GraphJwksController;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportSecurityProof;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKeyResolver;
import com.example.dispute.workflow.infrastructure.security.GraphJwkSetProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GraphShadowAssemblyTest {

    private static final String KEY_ID = "graph-shadow-key-1";

    @TempDir
    Path keyDirectory;

    @Test
    void disabledDefaultsBindWithoutConstructingAnyGraphCapability() {
        new ApplicationContextRunner()
                .withUserConfiguration(DefaultGraphPropertiesConfiguration.class)
                .withPropertyValues(
                        "app.agent-run-v2.graph-client.mode=DISABLED",
                        "app.agent-run-v2.graph-client.signing.key-directory=",
                        "app.agent-run-v2.graph-client.signing.active-key-id=",
                        "app.agent-run-v2.graph-client.registry.bindings[0].graph-key=",
                        "app.agent-run-v2.graph-client.registry.bindings[0].graph-version=",
                        "app.agent-run-v2.graph-client.registry.bindings[0].checkpoint-schema-version=",
                        "app.agent-run-v2.graph-client.registry.bindings[0].agent-profile-id=",
                        "app.agent-run-v2.graph-client.registry.bindings[0].prompt-profile-id=",
                        "app.agent-run-v2.graph-client.registry.bindings[0].model-profile-id=",
                        "app.agent-run-v2.graph-client.registry.bindings[0].output-schema-version=",
                        "app.agent-run-v2.graph-client.registry.bindings[0].policy-version=",
                        "app.agent-run-v2.graph-client.registry.bindings[0].guardrail-version=",
                        "app.agent-run-v2.graph-client.registry.bindings[0].audience=SYSTEM",
                        "app.agent-run-v2.graph-client.registry.bindings[0].registry-binding-hash=",
                        "app.agent-run-v2.graph-client.registry.bindings[0].tool-policy-version=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GraphCommandClientProperties.class).mode())
                            .isEqualTo(GraphCommandClientProperties.Mode.DISABLED);
                    assertThat(context.getBean(GraphShadowRegistryProperties.class).bindings())
                            .isEmpty();
                    assertThat(context).doesNotHaveBean(GraphTransportBundle.class);
                    assertThat(context).doesNotHaveBean(GraphEnvelopeSigningKey.class);
                    assertThat(context).doesNotHaveBean(AgentGraphCommandClient.class);
                });
    }

    @Test
    void shadowModeFailsClosedWithoutSigningAndRegistryConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(GraphSigningKeyConfiguration.class)
                .withPropertyValues("app.agent-run-v2.graph-client.mode=SHADOW")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("signing key directory is required");
                });

        new ApplicationContextRunner()
                .withUserConfiguration(GraphShadowRegistryConfiguration.class)
                .withPropertyValues("app.agent-run-v2.graph-client.mode=SHADOW")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("between one and 32 exact bindings");
                });
    }

    @Test
    void signingAndPublicJwksCapabilitiesCannotShareOneProcess() throws Exception {
        writeKeyPair(KEY_ID);

        new ApplicationContextRunner()
                .withUserConfiguration(GraphSigningKeyConfiguration.class)
                .withPropertyValues(
                        "app.agent-run-v2.graph-client.mode=SHADOW",
                        "app.agent-run-v2.graph-client.signing.key-directory=" + keyDirectory,
                        "app.agent-run-v2.graph-client.signing.active-key-id=" + KEY_ID,
                        "app.graph-jwks.enabled=true",
                        "app.graph-jwks.key-directory=" + keyDirectory)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("require separate processes");
                });

        new ApplicationContextRunner()
                .withUserConfiguration(GraphJwksConfiguration.class)
                .withPropertyValues(
                        "app.graph-jwks.enabled=true",
                        "app.graph-jwks.key-directory=" + keyDirectory,
                        "app.agent-run-v2.graph-client.mode=SHADOW",
                        "app.agent-run-v2.graph-client.base-uri=https://python-agent.internal")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("signing client to remain disabled");
                });
    }

    @Test
    void signedLocalShadowAssemblesTheCompleteSyntheticOnlyClientPath() throws Exception {
        writeKeyPair(KEY_ID);

        new ApplicationContextRunner()
                .withUserConfiguration(
                        GraphTransportConfiguration.class,
                        GraphSigningKeyConfiguration.class,
                        GraphShadowRegistryConfiguration.class,
                        GraphCommandClientConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues(
                        "app.agent-run-v2.graph-client.mode=SHADOW",
                        "app.agent-run-v2.graph-client.base-uri=http://127.0.0.1:18000",
                        "app.agent-run-v2.graph-client.allow-plaintext-transport=true",
                        "app.agent-run-v2.graph-client.signing.key-directory=" + keyDirectory,
                        "app.agent-run-v2.graph-client.signing.active-key-id=" + KEY_ID,
                        "app.agent-run-v2.graph-client.registry.bindings[0].graph-key=synthetic.shadow",
                        "app.agent-run-v2.graph-client.registry.bindings[0].graph-version=1.0.0",
                        "app.agent-run-v2.graph-client.registry.bindings[0].checkpoint-schema-version=checkpoint.v1",
                        "app.agent-run-v2.graph-client.registry.bindings[0].agent-profile-id=synthetic.agent.v1",
                        "app.agent-run-v2.graph-client.registry.bindings[0].prompt-profile-id=synthetic.prompt.v1",
                        "app.agent-run-v2.graph-client.registry.bindings[0].model-profile-id=synthetic.model.v1",
                        "app.agent-run-v2.graph-client.registry.bindings[0].output-schema-version=synthetic.output.v1",
                        "app.agent-run-v2.graph-client.registry.bindings[0].policy-version=synthetic.policy.v1",
                        "app.agent-run-v2.graph-client.registry.bindings[0].guardrail-version=synthetic.guardrail.v1",
                        "app.agent-run-v2.graph-client.registry.bindings[0].audience=SYSTEM",
                        "app.agent-run-v2.graph-client.registry.bindings[0].registry-binding-hash="
                                + "a".repeat(64),
                        "app.agent-run-v2.graph-client.registry.bindings[0].tool-policy-version=tools.none.v1")
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withBean(
                        AgentRunV2StreamStore.class,
                        () -> mock(AgentRunV2StreamStore.class))
                .withBean(
                        AgentRunReconciledFinalStore.class,
                        () -> mock(AgentRunReconciledFinalStore.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GraphEnvelopeSigningKeyResolver.class);
                    assertThat(context).hasSingleBean(GraphEnvelopeSigningKey.class);
                    assertThat(context).hasSingleBean(GraphRegistryBindingPolicy.class);
                    assertThat(context).hasSingleBean(GraphStreamVisibilityPolicy.class);
                    assertThat(context).hasSingleBean(GraphTransportBundle.class);
                    assertThat(context).hasSingleBean(AgentGraphCommandClient.class);
                    assertThat(context).hasSingleBean(AgentGraphReconciliationClient.class);
                    assertThat(context).hasSingleBean(AgentRunExecutionGateway.class);
                    assertThat(context.getBean(GraphTransportBundle.class)
                                    .transportProof()
                                    .mode())
                            .isEqualTo(GraphTransportSecurityProof.Mode.LOCAL_PLAINTEXT);
                });
    }

    @Test
    void jwksAssemblyReadsOnlyPublicKeysAndExposesNoSigningCapability() throws Exception {
        writePublicKey(KEY_ID, keyPair());

        new ApplicationContextRunner()
                .withUserConfiguration(
                        GraphJwksConfiguration.class,
                        GraphJwksController.class)
                .withPropertyValues(
                        "app.graph-jwks.enabled=true",
                        "app.graph-jwks.key-directory=" + keyDirectory)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GraphJwkSetProvider.class);
                    assertThat(context).hasSingleBean(GraphJwksController.class);
                    assertThat(context).doesNotHaveBean(GraphEnvelopeSigningKeyResolver.class);
                    assertThat(context).doesNotHaveBean(GraphEnvelopeSigningKey.class);
                    assertThat(context.getBean(GraphJwksController.class)
                                    .keys()
                                    .getBody()
                                    .keys())
                            .extracting(GraphJwkSetProvider.PublicJwk::kid)
                            .containsExactly(KEY_ID);
                });
    }

    private void writeKeyPair(String keyId) throws Exception {
        KeyPair pair = keyPair();
        writePublicKey(keyId, pair);
        writePem(
                keyId + ".private.pem",
                "PRIVATE KEY",
                pair.getPrivate().getEncoded());
    }

    private void writePublicKey(String keyId, KeyPair pair) throws Exception {
        writePem(
                keyId + ".public.pem",
                "PUBLIC KEY",
                pair.getPublic().getEncoded());
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private void writePem(String fileName, String type, byte[] encoded) throws Exception {
        String payload = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        Files.writeString(
                keyDirectory.resolve(fileName),
                "-----BEGIN " + type + "-----\n"
                        + payload
                        + "\n-----END " + type + "-----\n",
                StandardCharsets.US_ASCII);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
        GraphCommandClientProperties.class,
        GraphSigningKeyProperties.class,
        GraphShadowRegistryProperties.class,
        GraphTlsClientProperties.class,
        GraphJwksProperties.class
    })
    static class DefaultGraphPropertiesConfiguration {}
}

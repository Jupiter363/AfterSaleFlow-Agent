package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingConflictException;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrar;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IntakePrivateThreadRegistrationTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    static {
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void issuesTheExactFrozenRegistrationAndRfc8785Hash() throws Exception {
        var registration = IntakeTestFixtures.binding().registration();
        var fixture = MAPPER.readTree(
                Path.of(
                                "..",
                                "contracts",
                                "agent-platform",
                                "intake",
                                "v2",
                                "fixtures",
                                "valid",
                                "graph-private-thread-registration-valid.json")
                        .toFile());

        JsonNode actual = MAPPER.valueToTree(registration);
        assertThat(ContractJson.canonicalize(actual))
                .isEqualTo(ContractJson.canonicalize(fixture));
        assertThat(IntakeContractHashes.registrationHash(registration))
                .isEqualTo(fixture.required("registration_hash").asText());
        assertThat(registration.threadId()).matches("grt\\.v1\\.[0-9a-f]{32}");
    }

    @Test
    void exactReplayReturnsTheExistingRegistrationAndVersionDriftConflicts() {
        var store = new IntakeTestFixtures.SingleBindingStore();
        var generated = new AtomicInteger();
        var factory =
                new IntakePrivateThreadRegistrationFactory(
                        () -> {
                            generated.incrementAndGet();
                            return IntakeTestFixtures.THREAD_ID;
                        });
        var registrar = new IntakePrivateThreadRegistrar(store, factory);
        var request = IntakeTestFixtures.issueRequest("intake-prompt.v2");

        assertThat(registrar.register(request).created()).isTrue();
        assertThat(registrar.register(request).created()).isFalse();
        assertThat(generated).hasValue(1);
        assertThatThrownBy(
                        () ->
                                registrar.register(
                                        IntakeTestFixtures.issueRequest("intake-prompt.v3")))
                .isInstanceOf(IntakeGraphBindingConflictException.class);
    }
}

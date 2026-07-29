package com.example.dispute.agentstream.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.Pattern;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class AgentRunControllerValidationContractTest {

    @Test
    void privateSseAcceptsOnlyLegacyAndCanonicalTargetRoomRunIds() throws Exception {
        Method events = AgentRunController.class.getDeclaredMethod(
                "events", String.class, String.class, String.class,
                org.springframework.security.core.Authentication.class);
        String regex = events.getParameters()[0].getAnnotation(Pattern.class).regexp();

        assertThat(java.util.regex.Pattern.matches(regex, "AGENT_RUN_legacy123")).isTrue();
        assertThat(java.util.regex.Pattern.matches(
                        regex, "target-intake-run:18d370d2d183338a85ebd59feb68d388"))
                .isTrue();
        assertThat(java.util.regex.Pattern.matches(
                        regex, "target-evidence-run:18d370d2d183338a85ebd59feb68d388"))
                .isTrue();
        assertThat(java.util.regex.Pattern.matches(
                        regex, "target-hearing-run:18d370d2d183338a85ebd59feb68d388"))
                .isTrue();
        assertThat(java.util.regex.Pattern.matches(
                        regex, "target-review-run:18d370d2d183338a85ebd59feb68d388"))
                .isTrue();
        assertThat(java.util.regex.Pattern.matches(regex, "target-intake-run:ABCDEF0123456789abcdef0123456789"))
                .isFalse();
        assertThat(java.util.regex.Pattern.matches(regex, "target-intake-run:18d370d2d183338a85ebd59feb68d38"))
                .isFalse();
        assertThat(java.util.regex.Pattern.matches(regex, "target-intake-run:18d370d2d183338a85ebd59feb68d388x"))
                .isFalse();
        assertThat(java.util.regex.Pattern.matches(
                        regex, "target-outcome-run:18d370d2d183338a85ebd59feb68d388"))
                .isFalse();
    }
}

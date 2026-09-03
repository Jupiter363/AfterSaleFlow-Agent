package com.example.dispute.agentstream.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.Pattern;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunControllerValidationContractTest {

    @Test
    void allReadEndpointsAcceptOnlyLegacyAndCanonicalTargetRoomRunIds() throws Exception {
        List<String> regexes = List.of(
                runIdRegex("get", String.class,
                        org.springframework.security.core.Authentication.class,
                        jakarta.servlet.http.HttpServletRequest.class),
                runIdRegex("events", String.class, String.class, String.class,
                        org.springframework.security.core.Authentication.class),
                runIdRegex("replay", String.class, Long.class, String.class,
                        org.springframework.security.core.Authentication.class,
                        jakarta.servlet.http.HttpServletRequest.class));

        assertThat(regexes).containsOnly(AgentRunController.RUN_ID_PATTERN);

        for (String regex : regexes) {
            assertAccepted(regex, "AGENT_RUN_legacy123");
            assertAccepted(regex, "target-intake-run:18d370d2d183338a85ebd59feb68d388");
            assertAccepted(regex, "target-evidence-run:18d370d2d183338a85ebd59feb68d388");
            assertAccepted(regex, "target-hearing-run:18d370d2d183338a85ebd59feb68d388");
            assertAccepted(regex, "target-review-run:18d370d2d183338a85ebd59feb68d388");
            assertRejected(regex, "target-intake-run:ABCDEF0123456789abcdef0123456789");
            assertRejected(regex, "target-intake-run:18d370d2d183338a85ebd59feb68d38");
            assertRejected(regex, "target-intake-run:18d370d2d183338a85ebd59feb68d388x");
            assertRejected(regex, "target-outcome-run:18d370d2d183338a85ebd59feb68d388");
            assertRejected(regex, "AGENT-RUN-legacy123");
        }
    }

    private static String runIdRegex(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = AgentRunController.class.getDeclaredMethod(methodName, parameterTypes);
        return method.getParameters()[0].getAnnotation(Pattern.class).regexp();
    }

    private static void assertAccepted(String regex, String runId) {
        assertThat(java.util.regex.Pattern.matches(regex, runId)).isTrue();
    }

    private static void assertRejected(String regex, String runId) {
        assertThat(java.util.regex.Pattern.matches(regex, runId)).isFalse();
    }
}

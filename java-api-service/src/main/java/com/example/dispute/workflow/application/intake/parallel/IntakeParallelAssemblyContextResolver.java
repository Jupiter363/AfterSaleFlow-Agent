package com.example.dispute.workflow.application.intake.parallel;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.FrameSetAuthority;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphResultEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Resolves immutable, Java-trusted turn material that is intentionally absent from provider
 * Frame results.
 *
 * <p>The implementation must bind the returned dossier and source message to the exact snapshot,
 * event binding, run, attempt, command, and Frame-set authority supplied to {@link #resolve}. It
 * must never infer the current message from provider output or from the latest mutable room row.
 */
@FunctionalInterface
public interface IntakeParallelAssemblyContextResolver {

    TrustedTurnContext resolve(
            ExecuteAgentRunRequest request, FrameSetAuthority frameSetAuthority);

    record TrustedTurnContext(
            String sourceMessageId,
            String currentMessageText,
            long cognitiveRevision,
            JsonNode previousDossier,
            String executionProvider,
            String executionModel,
            String authorityActivationId) {

        private static final Pattern IDENTIFIER =
                Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

        public TrustedTurnContext {
            sourceMessageId = identifier(sourceMessageId, "sourceMessageId");
            if (currentMessageText == null
                    || currentMessageText.isBlank()
                    || currentMessageText.length() > 8_192) {
                throw new IllegalArgumentException(
                        "currentMessageText must contain 1..8192 characters");
            }
            currentMessageText = currentMessageText.strip();
            if (cognitiveRevision < 1) {
                throw new IllegalArgumentException("cognitiveRevision must be positive");
            }
            if (previousDossier == null || !previousDossier.isObject()) {
                throw new IllegalArgumentException("previousDossier must be one JSON object");
            }
            previousDossier = previousDossier.deepCopy();
            executionProvider = bounded(
                    executionProvider,
                    TargetE2EGraphResultEnvelope.EXECUTION_PROVIDER_MAX_LENGTH,
                    "executionProvider");
            executionModel = bounded(
                    executionModel,
                    TargetE2EGraphResultEnvelope.EXECUTION_MODEL_MAX_LENGTH,
                    "executionModel");
            if (authorityActivationId == null
                    || !authorityActivationId.matches("p9act[.]v1[.][0-9a-f]{32}")) {
                throw new IllegalArgumentException("authorityActivationId is invalid");
            }
        }

        @Override
        public JsonNode previousDossier() {
            return previousDossier.deepCopy();
        }

        private static String identifier(String value, String field) {
            if (value == null || !IDENTIFIER.matcher(value).matches()) {
                throw new IllegalArgumentException(field + " must be a bounded identifier");
            }
            return value;
        }

        private static String bounded(String value, int maximum, String field) {
            Objects.requireNonNull(value, field);
            if (value.isBlank() || value.length() > maximum) {
                throw new IllegalArgumentException(field + " is outside its bounded length");
            }
            return value;
        }
    }
}

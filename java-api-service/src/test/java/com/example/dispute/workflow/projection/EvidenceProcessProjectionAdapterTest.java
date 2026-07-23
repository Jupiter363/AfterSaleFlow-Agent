package com.example.dispute.workflow.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.api.EvidenceSubmissionRequest;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionAdapter;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionAdapter.ProjectionEvidenceState;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionAdapter.ProjectionRow;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.ActiveGraphRun;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.AssessmentCounts;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.PartyCompletion;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.Recovery;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.TerminalProposal;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.VersionPins;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class EvidenceProcessProjectionAdapterTest {

    private static final OffsetDateTime PROJECTED_AT =
            OffsetDateTime.parse("2026-07-22T03:04:05Z");
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private final EvidenceProcessProjectionAdapter adapter =
            new EvidenceProcessProjectionAdapter(mock(NamedParameterJdbcOperations.class));

    @Test
    @SuppressWarnings("unchecked")
    void readScopesEverySupportedViewerAndExcludesActiveRunsFromHistory() {
        for (ActorRole role : List.of(
                ActorRole.USER, ActorRole.MERCHANT, ActorRole.PLATFORM_REVIEWER)) {
            for (boolean historyMode : List.of(false, true)) {
                NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
                when(jdbc.query(
                                anyString(),
                                any(SqlParameterSource.class),
                                any(RowMapper.class)))
                        .thenReturn(List.of());
                EvidenceProcessProjectionAdapter scopedAdapter =
                        new EvidenceProcessProjectionAdapter(jdbc);
                AuthenticatedActor actor = actor(role);

                scopedAdapter.read("CASE_SAFE", actor, historyMode);

                ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
                ArgumentCaptor<SqlParameterSource> parameters =
                        ArgumentCaptor.forClass(SqlParameterSource.class);
                verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
                assertThat(sql.getValue())
                        .contains(
                                "epoch.room_type = 'EVIDENCE'",
                                "run.room_type = 'EVIDENCE'",
                                "run.stream_audience_json",
                                "run.stream_audience_actor_ids_json",
                                "cast(:historyMode as boolean) = false");
                assertThat(parameters.getValue().getValue("actorId"))
                        .isEqualTo(actor.actorId());
                assertThat(parameters.getValue().getValue("actorRole"))
                        .isEqualTo(role.name());
                assertThat(parameters.getValue().getValue("viewerScopeHash"))
                        .isEqualTo(scopeHash(actor));
                assertThat(parameters.getValue().getValue("historyMode"))
                        .isEqualTo(historyMode);
            }
        }
    }

    @Test
    void readRejectsPrivilegedServiceRolesBeforeQuerying() {
        for (ActorRole role : List.of(
                ActorRole.CUSTOMER_SERVICE, ActorRole.ADMIN, ActorRole.SYSTEM)) {
            NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
            EvidenceProcessProjectionAdapter scopedAdapter =
                    new EvidenceProcessProjectionAdapter(jdbc);

            assertThatThrownBy(() -> scopedAdapter.read("CASE_SAFE", actor(role), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsupported Evidence projection viewer");
            verifyNoInteractions(jdbc);
        }
    }

    @Test
    void mapsCurrentShadowTupleToTheCompleteFrozenWireContract() {
        AuthenticatedActor actor = actor(ActorRole.USER);
        EvidenceProcessProjectionView view = adapter.adapt(
                shadowRow(actor, false, true, "ASSESSING", "ACTIVE", pendingState()),
                actor);

        assertThat(view.schemaVersion()).isEqualTo("evidence-process-projection.v1");
        assertThat(view.projectionState()).isEqualTo("AVAILABLE");
        assertThat(view.writerMode()).isEqualTo("SHADOW");
        assertThat(view.graphRuntimeMode()).isEqualTo("SIGNED_SYNTHETIC_SHADOW");
        assertThat(view.formalSinkAllowed()).isFalse();
        assertThat(view.temporalEvidenceAllocationAllowed()).isFalse();
        assertThat(view.realCaseShadowAllowed()).isFalse();
        assertThat(view.viewerActorId()).isEqualTo(actor.actorId());
        assertThat(view.viewerActorRole()).isEqualTo("USER");
        assertThat(view.audience()).isEqualTo("USER");
        assertThat(view.viewerScopeHash()).isEqualTo(scopeHash(actor));
        assertThat(view.pendingState()).isEqualTo("AGENT_RUNNING");
        assertThat(view.pendingOperationKey())
                .isEqualTo(
                        "evidence.graph.request:CASE_P5_SYNTHETIC_1:4:"
                                + "a".repeat(64)
                                + ":RUN_P5_ONE");
        assertThat(view.activeGraphRun()).isNotNull();
        assertThat(view.versionPins().assessmentOutputSchemaVersion())
                .isEqualTo("evidence-item-assessment.v1");
        assertThat(view.versionPins().terminalOutputSchemaVersion())
                .isEqualTo("evidence-batch-proposal.v1");
        assertThat(view.originalDeadlineAt()).isEqualTo(PROJECTED_AT.plusDays(1));
        assertThat(view.projectedAt()).isEqualTo(PROJECTED_AT);
        assertSelfHash(view);
    }

    @Test
    void legacyProjectionUsesTheCompleteUnavailableFallbackContract() {
        AuthenticatedActor actor = actor(ActorRole.MERCHANT);
        EvidenceProcessProjectionView view = adapter.adapt(legacyRow(actor), actor);

        assertThat(view.projectionState()).isEqualTo("UNAVAILABLE");
        assertThat(view.writerMode()).isEqualTo("LEGACY");
        assertThat(view.graphRuntimeMode()).isEqualTo("DISABLED");
        assertThat(view.roomId()).isNull();
        assertThat(view.roomEpoch()).isZero();
        assertThat(view.fencingToken()).isZero();
        assertThat(view.versionPins().workflowBuildId()).isNull();
        assertThat(view.versionPins().promptVersion()).isNotBlank();
        assertThat(view.viewerActorRole()).isEqualTo("MERCHANT");
        assertSelfHash(view);
    }

    @Test
    void historyModeFailsClosedIfAnActiveRunLeaksIntoTheRow() {
        AuthenticatedActor actor = actor(ActorRole.USER);
        EvidenceProcessProjectionView view = adapter.adapt(
                shadowRow(actor, true, true, "ASSESSING", "ACTIVE", pendingState()),
                actor);

        assertThat(view.projectionState()).isEqualTo("PROCESSING");
        assertThat(view.historyMode()).isTrue();
        assertThat(view.pendingState()).isEqualTo("NONE");
        assertThat(view.pendingOperationKey()).isNull();
        assertThat(view.activeGraphRun()).isNull();
        assertSelfHash(view);
    }

    @Test
    void closedSyntheticHistoryMayRepresentOneHundredItemsWithoutPublicApproval() {
        AuthenticatedActor actor = actor(ActorRole.PLATFORM_REVIEWER);
        ProjectionEvidenceState closedState = new ProjectionEvidenceState(
                PROJECTED_AT.plusDays(1),
                false,
                null,
                new PartyCompletion(true, true, null, null, null, null),
                new AssessmentCounts(100, 92, 8, 0, 0),
                3L,
                100,
                "BOTH_PARTIES_COMPLETED",
                new TerminalProposal("PROPOSAL_P5_100", "b".repeat(64)),
                Recovery.none());

        EvidenceProcessProjectionView view = adapter.adapt(
                shadowRow(actor, true, false, "COMPLETED", "TERMINAL", closedState),
                actor);

        assertThat(view.projectionState()).isEqualTo("AVAILABLE");
        assertThat(view.historyMode()).isTrue();
        assertThat(view.roomPhase()).isEqualTo("COMPLETED");
        assertThat(view.assessmentCounts().manifestItemCount()).isEqualTo(100);
        assertThat(view.pendingState()).isEqualTo("NONE");
        assertThat(view.activeGraphRun()).isNull();
    }

    @Test
    void wireViewContainsEveryFrozenSchemaFieldAndAValidSelfHash() {
        AuthenticatedActor actor = actor(ActorRole.USER);
        EvidenceProcessProjectionView view =
                adapter.adapt(shadowRow(actor, false, false, "OPEN", "ACTIVE", pendingState()), actor);

        assertThat(Arrays.stream(EvidenceProcessProjectionView.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList())
                .containsExactly(
                        "schemaVersion",
                        "projectionHash",
                        "projectionState",
                        "tenantSurrogate",
                        "caseId",
                        "roomId",
                        "roomEpoch",
                        "fencingToken",
                        "writerMode",
                        "graphRuntimeMode",
                        "formalSinkAllowed",
                        "temporalEvidenceAllocationAllowed",
                        "realCaseShadowAllowed",
                        "viewerActorId",
                        "viewerActorRole",
                        "viewerScopeHash",
                        "audience",
                        "roomPhase",
                        "terminalReason",
                        "pendingState",
                        "pendingOperationKey",
                        "originalDeadlineAt",
                        "warningSent",
                        "warningSentAt",
                        "partyCompletion",
                        "assessmentCounts",
                        "dossierVersion",
                        "historyMode",
                        "lastEventSequence",
                        "activeGraphRun",
                        "terminalProposal",
                        "recovery",
                        "versionPins",
                        "processRevision",
                        "roomRevision",
                        "projectedAt");
        assertSelfHash(view);
    }

    @Test
    void requiredPinsAndProjectionTimestampFailClosed() {
        assertThatThrownBy(() -> new VersionPins(
                        null,
                        null,
                        null,
                        null,
                        null,
                        "evidence-model.synthetic.v1",
                        "evidence-item-assessment.v1",
                        "evidence-batch-proposal.v1",
                        "evidence-policy.v2",
                        "evidence-guardrail.v2",
                        "evidence-tools.synthetic.v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("promptVersion");

        AuthenticatedActor actor = actor(ActorRole.USER);
        ProjectionRow missingTimestamp = shadowRow(
                actor, false, false, "OPEN", "ACTIVE", pendingState(), null);
        assertThatThrownBy(() -> adapter.adapt(missingTimestamp, actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectedAt");
    }

    @Test
    void temporalUnknownAndRealCaseShadowModesFailClosed() {
        AuthenticatedActor actor = actor(ActorRole.USER);
        for (String mode : List.of("TEMPORAL", "UNKNOWN")) {
            ProjectionRow row = row(
                    actor,
                    mode,
                    "TENANT_P5_SYNTHETIC_1",
                    "CASE_P5_SYNTHETIC_1",
                    "ROOM_P5_EVIDENCE_1",
                    false,
                    false,
                    "OPEN",
                    "ACTIVE",
                    pendingState(),
                    PROJECTED_AT);
            assertThatThrownBy(() -> adapter.adapt(row, actor))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsupported Evidence writer mode");
        }

        ProjectionRow realShadow = row(
                actor,
                "SHADOW",
                "TENANT_REAL_1",
                "CASE_REAL_1",
                "ROOM_REAL_1",
                false,
                false,
                "OPEN",
                "ACTIVE",
                pendingState(),
                PROJECTED_AT);
        assertThatThrownBy(() -> adapter.adapt(realShadow, actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("real-case Evidence shadow is forbidden");
    }

    @Test
    void staleViewerRoleBindingFailsClosed() {
        AuthenticatedActor user = actor(ActorRole.USER);
        AuthenticatedActor merchant = actor(ActorRole.MERCHANT);
        ProjectionRow userScoped = shadowRow(
                user, false, false, "OPEN", "ACTIVE", pendingState());

        assertThatThrownBy(() -> adapter.adapt(userScoped, merchant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stale Evidence projection viewer binding");
    }

    @Test
    void publicSubmissionAcceptsAtMostFiftyEvidenceIdsAndRejectsFiftyOne() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(
                            new EvidenceSubmissionRequest(evidenceIds(50), "closed batch")))
                    .isEmpty();
            assertThat(validator.validate(
                            new EvidenceSubmissionRequest(evidenceIds(51), "too many")))
                    .singleElement()
                    .satisfies(violation ->
                            assertThat(violation.getPropertyPath().toString())
                                    .isEqualTo("evidenceIds"));
        }
    }

    private static ProjectionRow legacyRow(AuthenticatedActor actor) {
        return row(
                actor,
                "LEGACY",
                "legacy-default",
                "CASE_LEGACY_1",
                null,
                false,
                false,
                "WAITING_PARTIES",
                null,
                pendingState(),
                PROJECTED_AT);
    }

    private static ProjectionRow shadowRow(
            AuthenticatedActor actor,
            boolean historyMode,
            boolean activeRun,
            String phase,
            String lifecycle,
            ProjectionEvidenceState state) {
        return shadowRow(actor, historyMode, activeRun, phase, lifecycle, state, PROJECTED_AT);
    }

    private static ProjectionRow shadowRow(
            AuthenticatedActor actor,
            boolean historyMode,
            boolean activeRun,
            String phase,
            String lifecycle,
            ProjectionEvidenceState state,
            OffsetDateTime projectedAt) {
        return row(
                actor,
                "SHADOW",
                "TENANT_P5_SYNTHETIC_1",
                "CASE_P5_SYNTHETIC_1",
                "ROOM_P5_EVIDENCE_1",
                historyMode,
                activeRun,
                phase,
                lifecycle,
                state,
                projectedAt);
    }

    private static ProjectionRow row(
            AuthenticatedActor actor,
            String writerMode,
            String tenantSurrogate,
            String caseId,
            String roomId,
            boolean historyMode,
            boolean activeRun,
            String phase,
            String lifecycle,
            ProjectionEvidenceState state,
            OffsetDateTime projectedAt) {
        boolean legacy = "LEGACY".equals(writerMode);
        ActiveGraphRun graphRun = activeRun
                ? new ActiveGraphRun(
                        "COMMAND_P5_ONE",
                        "RUN_P5_ONE",
                        "ATTEMPT_P5_ONE_1",
                        "MANIFEST_P5_ONE",
                        "a".repeat(64),
                        "evidence.v2.0.0",
                        "evidence-checkpoint.v2",
                        "RUNNING")
                : null;
        return new ProjectionRow(
                tenantSurrogate,
                caseId,
                roomId,
                writerMode,
                "TERMINAL".equals(lifecycle) ? "TERMINAL" : "READY",
                legacy ? 0 : 4,
                12,
                legacy ? 0 : 9,
                phase,
                projectedAt,
                legacy ? null : "SHADOW",
                lifecycle,
                legacy ? null : "READY",
                legacy ? null : 4L,
                legacy ? null : 12L,
                legacy ? null : 7L,
                legacy ? null : 9L,
                legacy ? null : "evidence-workflow.synthetic.v1",
                legacy ? null : "evidence.v2.0.0",
                legacy ? null : "evidence-checkpoint.v2",
                activeRun,
                graphRun,
                state,
                historyMode,
                actor.actorId(),
                actor.role().name(),
                scopeHash(actor));
    }

    private static ProjectionEvidenceState pendingState() {
        return ProjectionEvidenceState.pending(PROJECTED_AT.plusDays(1));
    }

    private static AuthenticatedActor actor(ActorRole role) {
        return new AuthenticatedActor(role.name() + "_ACTOR", role);
    }

    private static String scopeHash(AuthenticatedActor actor) {
        ObjectNode scope = JsonNodeFactory.instance.objectNode();
        scope.put("actor_id", actor.actorId());
        scope.put("actor_role", actor.role().name());
        scope.put("audience", actor.role().name());
        return ContractJson.sha256Hex(scope);
    }

    private static void assertSelfHash(EvidenceProcessProjectionView view) {
        ObjectNode node = JSON.valueToTree(view);
        node.remove("projection_hash");
        assertThat(view.projectionHash()).isEqualTo(ContractJson.sha256Hex(node));
    }

    private static List<String> evidenceIds(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> "EVIDENCE_" + index)
                .toList();
    }
}

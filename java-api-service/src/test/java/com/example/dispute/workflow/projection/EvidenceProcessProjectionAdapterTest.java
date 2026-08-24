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
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionAdapter.StateResolution;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionAdapter.TargetActivationAuthority;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionQuery;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.ActiveGraphRun;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.AssessmentCounts;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.PartyCompletion;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.Recovery;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.TerminalProposal;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionView.VersionPins;
import com.example.dispute.workflow.targete2e.ingress.materialization.TargetIntakeRuntimePins;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final String TARGET_SYNTHETIC_PREFIX = "QA_TARGET_";
    private static final TargetIntakeRuntimePins TARGET_RUNTIME_PINS = new TargetIntakeRuntimePins(
            "case-build-p9",
            "agent-build-p9",
            "b".repeat(64),
            "graph-code-p9",
            "d".repeat(64),
            "all-rooms-agent.target-e2e.v1",
           "all-rooms-prompt.target-e2e.v2",
           "target-e2e.contract-blocked",
           "litellm",
           "all-rooms-policy.target-e2e.v1",
            "all-rooms-guardrail.target-e2e.v1",
            "tools.none.v1",
            "memory-p9",
            "envelope-key-p9");

    private final EvidenceProcessProjectionAdapter adapter =
            new EvidenceProcessProjectionAdapter(mock(NamedParameterJdbcOperations.class));
    private final EvidenceProcessProjectionAdapter targetAdapter =
            new EvidenceProcessProjectionAdapter(
                    mock(NamedParameterJdbcOperations.class), TARGET_RUNTIME_PINS);

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
                                "join fulfillment_dispute_case dispute",
                                "join case_access_session viewer",
                                "viewer.status = 'ACTIVE'",
                                "viewer.permission_scopes_json",
                                "participant.participant_status = 'ACTIVE'",
                                "epoch.room_type = 'EVIDENCE'",
                                "target_e2e_room_epoch_binding target_binding",
                                "target_e2e_case_reservation target_reservation",
                                "target_e2e_activation target_activation",
                                "run.room_type = 'EVIDENCE'",
                                "run.stream_audience_json",
                                "run.stream_audience_actor_ids_json",
                                "cast(:historyMode as boolean) = false");
                assertThat(parameters.getValue().getValue("actorId"))
                        .isEqualTo(actor.actorId());
                assertThat(parameters.getValue().getValue("actorRole"))
                        .isEqualTo(role.name());
                assertThat(parameters.getValue().getValue("requiredViewerScopes"))
                        .isEqualTo("[\"CASE_READ\",\"EVIDENCE_READ\"]");
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
    @SuppressWarnings("unchecked")
    void readReturnsEmptyForForeignCaseWithoutAnAuthoritativeViewerBinding() {
        NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
        when(jdbc.query(
                        anyString(),
                        any(SqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of());
        EvidenceProcessProjectionAdapter scopedAdapter = new EvidenceProcessProjectionAdapter(jdbc);
        AuthenticatedActor foreignActor = actor(ActorRole.USER);

        assertThat(scopedAdapter.read("CASE_FOREIGN", foreignActor, false)).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains(
                        "join fulfillment_dispute_case dispute",
                        "join case_access_session viewer",
                        "viewer.actor_id = :actorId",
                        "viewer.actor_role = :actorRole",
                        "viewer.permission_level = case",
                        "viewer.permission_scopes_json",
                        "participant.case_id = projection.case_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingDurableEnrichmentKeepsThePendingPlaceholderProcessing() {
        NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
        AuthenticatedActor actor = actor(ActorRole.USER);
        ProjectionRow pending =
                shadowRow(actor, false, false, "OPEN", "ACTIVE", pendingState());
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(pending));
        EvidenceProcessProjectionQuery query = new EvidenceProcessProjectionQuery(
                new EvidenceProcessProjectionAdapter(jdbc), List.of());

        EvidenceProcessProjectionView view =
                query.read(pending.caseId(), actor, false).orElseThrow();

        assertThat(view.projectionState()).isEqualTo("PROCESSING");
        assertThat(view.dossierVersion()).isNull();
        assertThat(view.terminalProposal()).isNull();
        assertThat(view.recovery()).isEqualTo(Recovery.none());
    }

    @Test
    @SuppressWarnings("unchecked")
    void identicalTargetRowsDeduplicateBeforeAuthoritativeAvailabilityResolution() {
        NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
        AuthenticatedActor actor = actor(ActorRole.MERCHANT);
        ProjectionRow target = row(
                actor,
                "TEMPORAL",
                "tenant-run001",
                "QA_TARGET_0042",
                "ROOM_P9_EVIDENCE_42",
                false,
                false,
                "OPEN",
                "ACTIVE",
                pendingState(),
                PROJECTED_AT);
        ProjectionRow materiallyDifferent = row(
                actor,
                "TEMPORAL",
                "tenant-run001",
                "QA_TARGET_0042",
                "ROOM_P9_EVIDENCE_42",
                false,
                false,
                "OPEN",
                "ACTIVE",
                pendingState(),
                PROJECTED_AT.plusSeconds(1));
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(target, target), List.of(target, materiallyDifferent));
        EvidenceProcessProjectionQuery query = new EvidenceProcessProjectionQuery(
                new EvidenceProcessProjectionAdapter(jdbc, TARGET_RUNTIME_PINS), List.of());

        EvidenceProcessProjectionView view =
                query.read(target.caseId(), actor, false).orElseThrow();

        assertThat(view.projectionState()).isEqualTo("AVAILABLE");
        assertThat(view.writerMode()).isEqualTo("TEMPORAL");
        assertThat(view.graphRuntimeMode()).isEqualTo("TARGET_E2E_CANDIDATE");
        assertThat(view.roomPhase()).isEqualTo("OPEN");
        assertThat(view.pendingState()).isEqualTo("NONE");
        assertThat(view.activeGraphRun()).isNull();
        assertThat(view.recovery()).isEqualTo(Recovery.none());

        EvidenceProcessProjectionView ambiguous =
                query.read(target.caseId(), actor, false).orElseThrow();

        assertThat(ambiguous.projectionState()).isEqualTo("PROCESSING");
    }

    @Test
    @SuppressWarnings("unchecked")
    void advancedHearingProjectionReadsExactTerminalEvidenceEpochAuthority() {
        AuthenticatedActor actor = actor(ActorRole.MERCHANT);
        ProjectionEvidenceState closedState = new ProjectionEvidenceState(
                PROJECTED_AT.plusDays(1),
                false,
                null,
                new PartyCompletion(
                        true,
                        true,
                        "RECEIPT_EVIDENCE_USER",
                        "a".repeat(64),
                        "RECEIPT_EVIDENCE_MERCHANT",
                        "b".repeat(64)),
                new AssessmentCounts(1, 1, 0, 0, 0),
                1L,
                6,
                "BOTH_PARTIES_COMPLETED",
                new TerminalProposal("PROPOSAL_EVIDENCE_TERMINAL", "c".repeat(64)),
                Recovery.none());
        ProjectionRow terminal = targetEvidenceRow(
                actor,
                false,
                "ROOM_EVIDENCE_TERMINAL",
                "TERMINAL",
                0,
                14,
                2,
                6,
                closedState,
                targetAuthority(
                        "tenant-run001",
                        "QA_TARGET_0097",
                        "ISOLATED_SYNTHETIC_NEW_CASES",
                        TARGET_SYNTHETIC_PREFIX,
                        "ISOLATED_SYNTHETIC_NEW_CASE",
                        "EVIDENCE",
                        0,
                        2));
        ProjectionRow terminalHistory = targetEvidenceRow(
                actor,
                true,
                "ROOM_EVIDENCE_TERMINAL",
                "TERMINAL",
                0,
                14,
                2,
                6,
                closedState,
                terminal.targetAuthority());
        NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(terminal), List.of(terminalHistory), List.of(terminal));
        EvidenceProcessProjectionAdapter scoped =
                new EvidenceProcessProjectionAdapter(jdbc, TARGET_RUNTIME_PINS);

        EvidenceProcessProjectionView activeRead = scoped.read(
                        terminal.caseId(),
                        actor,
                        false,
                        ignored -> new StateResolution(closedState, true))
                .orElseThrow();
        EvidenceProcessProjectionView historyRead = scoped.read(
                        terminal.caseId(),
                        actor,
                        true,
                        ignored -> new StateResolution(closedState, true))
                .orElseThrow();
        EvidenceProcessProjectionView replay = scoped.read(
                        terminal.caseId(),
                        actor,
                        false,
                        ignored -> new StateResolution(closedState, true))
                .orElseThrow();

        assertThat(activeRead).isEqualTo(replay);
        assertThat(activeRead.roomId()).isEqualTo("ROOM_EVIDENCE_TERMINAL");
        assertThat(activeRead.roomEpoch()).isZero();
        assertThat(activeRead.fencingToken()).isEqualTo(2);
        assertThat(activeRead.processRevision()).isEqualTo(14);
        assertThat(activeRead.roomRevision()).isEqualTo(6);
        assertThat(activeRead.roomPhase()).isEqualTo("COMPLETED");
        assertThat(activeRead.pendingState()).isEqualTo("NONE");
        assertThat(activeRead.activeGraphRun()).isNull();
        assertThat(historyRead.historyMode()).isTrue();
        assertThat(historyRead.roomId()).isEqualTo(activeRead.roomId());
        assertThat(historyRead.roomEpoch()).isEqualTo(activeRead.roomEpoch());
        assertThat(historyRead.fencingToken()).isEqualTo(activeRead.fencingToken());
        assertThat(historyRead.roomPhase()).isEqualTo("COMPLETED");
        assertThat(historyRead.pendingState()).isEqualTo("NONE");
        assertThat(historyRead.activeGraphRun()).isNull();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(3))
                .query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains(
                        "with evidence_candidates as",
                        "projection.current_room = 'EVIDENCE'",
                        "coalesce(projection.current_room, '') <> 'EVIDENCE'",
                        "candidate.lifecycle_status = 'TERMINAL'",
                        "(later.room_epoch, later.fencing_token) >",
                        "(select count(*) from authoritative_candidates) = 1",
                        "coalesce(epoch.room_epoch, projection.room_epoch)",
                        "as projection_room_epoch",
                        "coalesce(epoch.fencing_token, projection.fencing_token)",
                        "as projection_fencing_token",
                        "then 'COMPLETED'",
                        "then epoch.terminal_at");

        ProjectionRow currentActive = row(
                actor,
                "TEMPORAL",
                "tenant-run001",
                "QA_TARGET_0097",
                "ROOM_EVIDENCE_ACTIVE",
                false,
                false,
                "OPEN",
                "ACTIVE",
                pendingState(),
                PROJECTED_AT);
        assertThat(targetAdapter.adapt(currentActive, actor).projectionState())
                .isEqualTo("AVAILABLE");

        ProjectionRow missingTerminal = targetEvidenceRow(
                actor, false, null, "TERMINAL", 0, 14, 2, 6, closedState, null);
        assertThatThrownBy(() -> targetAdapter.adapt(missingTerminal, actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lacks its activation ledger binding");

        TargetActivationAuthority drifted = targetAuthority(
                "tenant-run001",
                "QA_TARGET_0097",
                "ISOLATED_SYNTHETIC_NEW_CASES",
                TARGET_SYNTHETIC_PREFIX,
                "ISOLATED_SYNTHETIC_NEW_CASE",
                "EVIDENCE",
                0,
                3);
        assertThatThrownBy(() -> targetAdapter.adapt(
                        targetEvidenceRow(
                                actor,
                                false,
                                "ROOM_EVIDENCE_TERMINAL",
                                "TERMINAL",
                                0,
                                14,
                                2,
                                6,
                                closedState,
                                drifted),
                        actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lacks its activation ledger binding");

        TargetActivationAuthority hearing = targetAuthority(
                "tenant-run001",
                "QA_TARGET_0097",
                "ISOLATED_SYNTHETIC_NEW_CASES",
                TARGET_SYNTHETIC_PREFIX,
                "ISOLATED_SYNTHETIC_NEW_CASE",
                "HEARING",
                0,
                2);
        assertThatThrownBy(() -> targetAdapter.adapt(
                        targetEvidenceRow(
                                actor,
                                false,
                                "ROOM_EVIDENCE_TERMINAL",
                                "TERMINAL",
                                0,
                                14,
                                2,
                                6,
                                closedState,
                                hearing),
                        actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lacks its activation ledger binding");

        NamedParameterJdbcOperations ambiguousJdbc = mock(NamedParameterJdbcOperations.class);
        ProjectionRow conflicting = targetEvidenceRow(
                actor,
                false,
                "ROOM_EVIDENCE_CONFLICT",
                "TERMINAL",
                0,
                14,
                2,
                7,
                closedState,
                terminal.targetAuthority());
        when(ambiguousJdbc.query(
                        anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(terminal, conflicting));
        EvidenceProcessProjectionView ambiguous = new EvidenceProcessProjectionAdapter(
                        ambiguousJdbc, TARGET_RUNTIME_PINS)
                .read(
                        terminal.caseId(),
                        actor,
                        false,
                        ignored -> new StateResolution(closedState, true))
                .orElseThrow();
        assertThat(ambiguous.projectionState()).isEqualTo("PROCESSING");
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialDurableEnrichmentRunsAfterTheBaseQueryAndCannotAuthorizeAvailability() {
        NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
        AuthenticatedActor actor = actor(ActorRole.USER);
        ProjectionRow pending = shadowRow(
                actor, false, false, "READY_TO_FREEZE", "ACTIVE", pendingState());
        AtomicBoolean baseQueryReturned = new AtomicBoolean();
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(ignored -> {
                    baseQueryReturned.set(true);
                    return List.of(pending);
                });
        EvidenceProcessProjectionQuery.StateEnricher partial = (row, viewer, current) -> {
            assertThat(baseQueryReturned.get()).isTrue();
            return new ProjectionEvidenceState(
                    current.originalDeadlineAt(),
                    current.warningSent(),
                    current.warningSentAt(),
                    current.partyCompletion(),
                    current.assessmentCounts(),
                    3L,
                    current.lastEventSequence(),
                    current.terminalReason(),
                    new TerminalProposal("RECEIPT_P5_TERMINAL", "b".repeat(64)),
                    current.recovery());
        };
        EvidenceProcessProjectionQuery query = new EvidenceProcessProjectionQuery(
                new EvidenceProcessProjectionAdapter(jdbc), List.of(partial));

        EvidenceProcessProjectionView view =
                query.read(pending.caseId(), actor, false).orElseThrow();

        assertThat(view.projectionState()).isEqualTo("PROCESSING");
        assertThat(view.dossierVersion()).isEqualTo(3L);
        assertThat(view.terminalProposal())
                .isEqualTo(new TerminalProposal("RECEIPT_P5_TERMINAL", "b".repeat(64)));
        assertThat(view.assessmentCounts()).isEqualTo(AssessmentCounts.empty());
        assertThat(view.lastEventSequence()).isZero();
    }

    @Test
    void mapsCurrentShadowTupleToTheCompleteFrozenWireContract() throws IOException {
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
        assertFrozenSchemaValid(view);
    }

    @Test
    void mapsTargetTemporalTupleUsingItsBoundActivationAndTargetProfilePins()
            throws IOException {
        AuthenticatedActor actor = actor(ActorRole.USER);
        EvidenceProcessProjectionView view = targetAdapter.adapt(
                row(
                        actor,
                        "TEMPORAL",
                        "tenant-run001",
                        "QA_TARGET_0001",
                        "ROOM_P9_EVIDENCE_1",
                        false,
                        false,
                        "WAITING_TIMER",
                        "ACTIVE",
                        pendingState(),
                        PROJECTED_AT),
                actor);

        assertThat(view.projectionState()).isEqualTo("AVAILABLE");
        assertThat(view.tenantSurrogate()).isEqualTo("tenant-run001");
        assertThat(view.caseId()).isEqualTo("QA_TARGET_0001");
        assertThat(view.writerMode()).isEqualTo("TEMPORAL");
        assertThat(view.graphRuntimeMode()).isEqualTo("TARGET_E2E_CANDIDATE");
        assertThat(view.formalSinkAllowed()).isFalse();
        assertThat(view.temporalEvidenceAllocationAllowed()).isTrue();
        assertThat(view.realCaseShadowAllowed()).isFalse();
        assertThat(view.pendingState()).isEqualTo("WAITING_TIMER");
        assertThat(view.versionPins())
                .isEqualTo(VersionPins.target(
                        "control-build-p9",
                        "target-e2e-graph.2026-08-18.1",
                        "target-e2e-checkpoint.v2",
                        "all-rooms-prompt.target-e2e.v2",
                        "target-e2e.contract-blocked",
                        "all-rooms-policy.target-e2e.v1",
                        "all-rooms-guardrail.target-e2e.v1",
                        "tools.none.v1"));
        assertSelfHash(view);
        assertFrozenSchemaValid(view);
    }

    @Test
    void mapsExplicitCaseIdDeclaredByTheActivationLedger() {
        AuthenticatedActor actor = actor(ActorRole.PLATFORM_REVIEWER);
        String explicitCaseId = "PRECREATED_CASE_42";
        ProjectionRow explicit = row(
                actor,
                "TEMPORAL",
                "tenant-run001",
                explicitCaseId,
                "ROOM_P9_EVIDENCE_42",
                false,
                false,
                "OPEN",
                "ACTIVE",
                pendingState(),
                PROJECTED_AT,
                targetAuthority(
                        "tenant-run001",
                        explicitCaseId,
                        "EXPLICIT_CASE_IDS",
                        null,
                        "EXPLICIT_CASE_ID"));

        EvidenceProcessProjectionView view = targetAdapter.adapt(explicit, actor);

        assertThat(view.projectionState()).isEqualTo("AVAILABLE");
        assertThat(view.caseId()).isEqualTo(explicitCaseId);
        assertThat(view.graphRuntimeMode()).isEqualTo("TARGET_E2E_CANDIDATE");
    }

    @Test
    void legacyProjectionUsesTheCompleteUnavailableFallbackContract() throws IOException {
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
        assertFrozenSchemaValid(view);
    }

    @Test
    void historyModeFailsClosedIfAnActiveRunLeaksIntoTheRow() throws IOException {
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
        assertFrozenSchemaValid(view);
    }

    @Test
    void closedSyntheticHistoryMayRepresentOneHundredItemsWithoutPublicApproval() throws IOException {
        AuthenticatedActor actor = actor(ActorRole.PLATFORM_REVIEWER);
        ProjectionEvidenceState closedState = new ProjectionEvidenceState(
                PROJECTED_AT.plusDays(1),
                false,
                null,
                new PartyCompletion(
                        true,
                        true,
                        "RECEIPT_INITIATOR_P5_100",
                        "c".repeat(64),
                        "RECEIPT_RESPONDENT_P5_100",
                        "d".repeat(64)),
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
        assertFrozenSchemaValid(view);
    }

    @Test
    void wireViewContainsEveryFrozenSchemaFieldAndAValidSelfHash() throws IOException {
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
        assertFrozenSchemaValid(view);
    }

    @Test
    void nestedFrozenContractInvariantsFailClosed() {
        assertThatThrownBy(() -> new PartyCompletion(true, false, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initiator completion");
        assertThatThrownBy(() -> new PartyCompletion(false, false, "RECEIPT", "a".repeat(64), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initiator completion");
        assertThatThrownBy(() -> new AssessmentCounts(101, 101, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assessment counts");
        assertThatThrownBy(() -> new Recovery("NONE", true, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NONE recovery");
        assertThatThrownBy(() -> new Recovery("RESUMABLE", false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resumable recovery");
        assertThatThrownBy(() -> new VersionPins(
                        "evidence-workflow.synthetic.v1",
                        null,
                        null,
                        null,
                        "evidence-prompt.v2",
                        "evidence-model.synthetic.v1",
                        "evidence-item-assessment.v1",
                        "evidence-batch-proposal.v1",
                        "evidence-policy.v2",
                        "evidence-guardrail.v2",
                        "evidence-tools.synthetic.v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtime pins");

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

        assertThatThrownBy(() -> VersionPins.target(
                        "control-build-p9",
                        "target-e2e-graph.2026-08-18.1",
                        "target-e2e-checkpoint.v2",
                        "evidence-prompt.v2",
                        "target-e2e.contract-blocked",
                        "all-rooms-policy.target-e2e.v1",
                        "all-rooms-guardrail.target-e2e.v1",
                        "tools.none.v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target promptVersion");

        AuthenticatedActor actor = actor(ActorRole.USER);
        ProjectionRow missingTimestamp = shadowRow(
                actor, false, false, "OPEN", "ACTIVE", pendingState(), null);
        assertThatThrownBy(() -> adapter.adapt(missingTimestamp, actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectedAt");
    }

    @Test
    void serializedProjectionFailsTheFrozenSchemaForNestedInvalidValues() throws IOException {
        JsonSchema schema = frozenProjectionSchema();

        ObjectNode missingCompletionReceipt = serializedShadowProjection();
        ((ObjectNode) missingCompletionReceipt.get("party_completion"))
                .put("initiator_completed", true);
        assertThat(schema.validate(missingCompletionReceipt)).isNotEmpty();

        ObjectNode excessiveCount = serializedShadowProjection();
        ((ObjectNode) excessiveCount.get("assessment_counts")).put("manifest_item_count", 101);
        assertThat(schema.validate(excessiveCount)).isNotEmpty();

        ObjectNode invalidRecovery = serializedShadowProjection();
        ((ObjectNode) invalidRecovery.get("recovery")).put("retryable", true);
        assertThat(schema.validate(invalidRecovery)).isNotEmpty();
    }

    @Test
    void unknownAndOutOfScopeTargetRuntimeModesFailClosed() {
        AuthenticatedActor actor = actor(ActorRole.USER);
        ProjectionRow unknown = row(
                actor,
                "UNKNOWN",
                "TENANT_P5_SYNTHETIC_1",
                "CASE_P5_SYNTHETIC_1",
                "ROOM_P5_EVIDENCE_1",
                false,
                false,
                "OPEN",
                "ACTIVE",
                pendingState(),
                PROJECTED_AT);
        assertThatThrownBy(() -> adapter.adapt(unknown, actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported Evidence writer mode");

        ProjectionRow nonSyntheticTemporal = row(
                actor,
                "TEMPORAL",
                "tenant-p9-isolated-01",
                "CASE_REAL_1",
                "ROOM_P9_EVIDENCE_1",
                false,
                false,
                "OPEN",
                "ACTIVE",
                pendingState(),
                PROJECTED_AT);
        assertThatThrownBy(() -> adapter.adapt(nonSyntheticTemporal, actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activation case scope");

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
    void targetProjectionFailsClosedWhenDeploymentProfilePinsAreUnavailable() {
        AuthenticatedActor actor = actor(ActorRole.USER);
        ProjectionRow target = row(
                actor,
                "TEMPORAL",
                "tenant-run001",
                "QA_TARGET_0002",
                "ROOM_P9_EVIDENCE_2",
                false,
                false,
                "OPEN",
                "ACTIVE",
                pendingState(),
                PROJECTED_AT);

        assertThatThrownBy(() -> adapter.adapt(target, actor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("runtime profile pins are unavailable");
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
        TargetActivationAuthority targetAuthority = "TEMPORAL".equals(writerMode)
                ? targetAuthority(
                        tenantSurrogate,
                        caseId,
                        "ISOLATED_SYNTHETIC_NEW_CASES",
                        TARGET_SYNTHETIC_PREFIX,
                        "ISOLATED_SYNTHETIC_NEW_CASE")
                : null;
        return row(
                actor,
                writerMode,
                tenantSurrogate,
                caseId,
                roomId,
                historyMode,
                activeRun,
                phase,
                lifecycle,
                state,
                projectedAt,
                targetAuthority);
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
            OffsetDateTime projectedAt,
            TargetActivationAuthority targetAuthority) {
        boolean legacy = "LEGACY".equals(writerMode);
        boolean target = "TEMPORAL".equals(writerMode);
        ActiveGraphRun graphRun = activeRun
                ? new ActiveGraphRun(
                        "COMMAND_P5_ONE",
                        "RUN_P5_ONE",
                        "ATTEMPT_P5_ONE_1",
                        "MANIFEST_P5_ONE",
                        "a".repeat(64),
                        target ? "target-e2e-graph.2026-08-18.1" : "evidence.v2.0.0",
                        target ? "target-e2e-checkpoint.v2" : "evidence-checkpoint.v2",
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
                legacy ? null : writerMode,
                lifecycle,
                legacy ? null : "READY",
                legacy ? null : 4L,
                legacy ? null : 12L,
                legacy ? null : 7L,
                legacy ? null : 9L,
                legacy ? null : target ? "control-build-p9" : "evidence-workflow.synthetic.v1",
                legacy ? null : target ? "target-e2e-graph.2026-08-18.1" : "evidence.v2.0.0",
                legacy ? null : target ? "target-e2e-checkpoint.v2" : "evidence-checkpoint.v2",
                targetAuthority,
                activeRun,
                graphRun,
                state,
                historyMode,
                actor.actorId(),
                actor.role().name());
    }

    private static TargetActivationAuthority targetAuthority(
            String tenantSurrogate,
            String caseId,
            String caseScopeMode,
            String caseIdPrefix,
            String reservationKind) {
        return targetAuthority(
                tenantSurrogate,
                caseId,
                caseScopeMode,
                caseIdPrefix,
                reservationKind,
                "EVIDENCE",
                4,
                9);
    }

    private static TargetActivationAuthority targetAuthority(
            String tenantSurrogate,
            String caseId,
            String caseScopeMode,
            String caseIdPrefix,
            String reservationKind,
            String roomType,
            long roomEpoch,
            long roomFencingToken) {
        return new TargetActivationAuthority(
                "p9act.v1." + "a".repeat(32),
                "c".repeat(64),
                "TARGET_E2E_CANDIDATE",
                tenantSurrogate,
                tenantSurrogate,
                caseId,
                roomType,
                roomEpoch,
                roomFencingToken,
                "p9case.v1." + "f".repeat(32),
                reservationKind,
                "e".repeat(64),
                caseScopeMode,
                "e".repeat(64),
                caseIdPrefix,
                "case-build-p9",
                "control-build-p9",
                "agent-build-p9",
                "all-rooms.target-e2e.v2",
                "target-e2e-graph.2026-08-18.1",
                "target-e2e-checkpoint.v2",
                "b".repeat(64),
                "graph-code-p9",
                "d".repeat(64),
                "ACTIVE");
    }

    private static ProjectionRow targetEvidenceRow(
            AuthenticatedActor actor,
            boolean historyMode,
            String roomId,
            String lifecycle,
            long roomEpoch,
            long processRevision,
            long fencingToken,
            long roomRevision,
            ProjectionEvidenceState state,
            TargetActivationAuthority authority) {
        return new ProjectionRow(
                "tenant-run001",
                "QA_TARGET_0097",
                roomId,
                "TEMPORAL",
                "TERMINAL".equals(lifecycle) ? "TERMINAL" : "READY",
                roomEpoch,
                processRevision,
                fencingToken,
                "TERMINAL".equals(lifecycle) ? "COMPLETED" : "OPEN",
                PROJECTED_AT,
                "TEMPORAL",
                lifecycle,
                "READY",
                roomId == null ? null : roomEpoch,
                roomId == null ? null : processRevision,
                roomId == null ? null : roomRevision,
                roomId == null ? null : fencingToken,
                roomId == null ? null : "control-build-p9",
                roomId == null ? null : "target-e2e-graph.2026-08-18.1",
                roomId == null ? null : "target-e2e-checkpoint.v2",
                authority,
                false,
                null,
                state,
                historyMode,
                actor.actorId(),
                actor.role().name());
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

    private static JsonSchema frozenProjectionSchema() throws IOException {
        Path path = Path.of(
                "..",
                "contracts",
                "agent-platform",
                "evidence",
                "v2",
                "evidence-process-projection.schema.json");
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(JSON.readTree(Files.readString(path)));
    }

    private static void assertFrozenSchemaValid(EvidenceProcessProjectionView view) throws IOException {
        Set<?> errors = frozenProjectionSchema().validate(JSON.valueToTree(view));
        assertThat(errors).isEmpty();
    }

    private ObjectNode serializedShadowProjection() {
        AuthenticatedActor actor = actor(ActorRole.USER);
        return JSON.valueToTree(
                adapter.adapt(shadowRow(actor, false, false, "OPEN", "ACTIVE", pendingState()), actor));
    }
}

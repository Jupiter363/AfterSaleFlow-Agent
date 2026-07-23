package com.example.dispute.workflow.projection.evidence;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.workflow.projection.evidence.EvidenceProcessProjectionAdapter.StateResolution;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

/** Public read boundary for the actor-scoped Evidence process projection. */
@Component
public class EvidenceProcessProjectionQuery {

    private final EvidenceProcessProjectionAdapter adapter;
    private final List<StateEnricher> stateEnrichers;

    public EvidenceProcessProjectionQuery(
            EvidenceProcessProjectionAdapter adapter, List<StateEnricher> stateEnrichers) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.stateEnrichers = new ArrayList<>(stateEnrichers);
        AnnotationAwareOrderComparator.sort(this.stateEnrichers);
    }

    public Optional<EvidenceProcessProjectionView> read(
            String caseId, AuthenticatedActor actor, boolean historyMode) {
        return adapter.read(caseId, actor, historyMode, row -> enrich(row, actor));
    }

    private StateResolution enrich(
            EvidenceProcessProjectionAdapter.ProjectionRow row, AuthenticatedActor actor) {
        EvidenceProcessProjectionAdapter.ProjectionEvidenceState state = row.evidenceState();
        boolean authoritativelyComplete = false;
        for (StateEnricher enricher : stateEnrichers) {
            state = Objects.requireNonNull(
                    enricher.enrich(row, actor, state),
                    () -> enricher.getClass().getName() + " returned a null Evidence projection state");
            if (enricher instanceof CompleteStateEnricher completeStateEnricher) {
                authoritativelyComplete |= completeStateEnricher.isAuthoritativelyComplete(
                        row, actor, state);
            }
        }
        return new StateResolution(state, authoritativelyComplete);
    }

    /**
     * Adds only Java-durable facts to the public projection. Implementations must not query Temporal
     * workflow memory, Graph checkpoints, InternalEvidenceController, or client-supplied state.
     */
    public interface StateEnricher {
        EvidenceProcessProjectionAdapter.ProjectionEvidenceState enrich(
                EvidenceProcessProjectionAdapter.ProjectionRow row,
                AuthenticatedActor actor,
                EvidenceProcessProjectionAdapter.ProjectionEvidenceState current);
    }

    /** Marker for a reader that hydrates every Java-owned field from one durable snapshot. */
    public interface CompleteStateEnricher extends StateEnricher {
        boolean isAuthoritativelyComplete(
                EvidenceProcessProjectionAdapter.ProjectionRow row,
                AuthenticatedActor actor,
                EvidenceProcessProjectionAdapter.ProjectionEvidenceState enriched);
    }
}

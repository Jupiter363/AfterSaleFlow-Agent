package com.example.dispute.workflow.projection.evidence;

import com.example.dispute.config.AuthenticatedActor;
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

    private EvidenceProcessProjectionAdapter.ProjectionEvidenceState enrich(
            EvidenceProcessProjectionAdapter.ProjectionRow row, AuthenticatedActor actor) {
        EvidenceProcessProjectionAdapter.ProjectionEvidenceState state = row.evidenceState();
        for (StateEnricher enricher : stateEnrichers) {
            state = Objects.requireNonNull(
                    enricher.enrich(row, actor, state),
                    () -> enricher.getClass().getName() + " returned a null Evidence projection state");
        }
        return state;
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
}

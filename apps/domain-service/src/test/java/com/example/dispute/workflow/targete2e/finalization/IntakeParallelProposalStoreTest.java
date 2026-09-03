package com.example.dispute.workflow.targete2e.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class IntakeParallelProposalStoreTest {

    private static final String HASH = "a".repeat(64);
    private static final String ARTIFACT_ID = "intake.proposal." + HASH.substring(0, 32);
    private static final String URI = "urn:target-e2e:proposal:intake:" + HASH;

    @Test
    void jdbcStoreResolvesAndReadsTheExactCanonicalBytes() throws Exception {
        byte[] payload = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<Map<String, ?>>any(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getString("artifact_id")).thenReturn(ARTIFACT_ID);
                    when(resultSet.getString("schema_version"))
                            .thenReturn("intake-turn-proposal.v2");
                    when(resultSet.getString("artifact_uri")).thenReturn(URI);
                    when(resultSet.getString("proposal_sha256")).thenReturn(HASH);
                    when(resultSet.getLong("size_bytes")).thenReturn((long) payload.length);
                    when(resultSet.getBytes("canonical_proposal_bytes")).thenReturn(payload);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        var store = new JdbcIntakeParallelProposalStore(jdbc);
        var pointer = pointer(URI);

        var metadata = store.resolve(pointer);
        var stored = store.readExact(new IntakeProposalReference(
                ARTIFACT_ID,
                "intake-turn-proposal.v2",
                URI,
                HASH,
                HASH,
                payload.length));

        assertThat(metadata.objectVersion()).isEqualTo(HASH);
        assertThat(metadata.sizeBytes()).isEqualTo(payload.length);
        assertThat(stored.payload()).containsExactly(payload);
        verify(jdbc, org.mockito.Mockito.times(2))
                .query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<Map<String, ?>>any(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any());
    }

    @Test
    void jdbcStoreRejectsNonCanonicalParallelReferenceBeforeQuery() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        var store = new JdbcIntakeParallelProposalStore(jdbc);

        assertThatThrownBy(() -> store.resolve(pointer("urn:target-e2e:proposal:intake:" + "b".repeat(64))))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .extracting(failure -> ((IntakeFinalizationRejectedException) failure).code())
                .isEqualTo("INTAKE_PROPOSAL_URI_FORBIDDEN");
        verify(jdbc, never()).query(anyString(), any(Map.class), any(RowMapper.class));
    }

    @Test
    void routerUsesOnlyTheStoreSelectedByTheExplicitUriAuthority() {
        CountingStore minio = new CountingStore();
        CountingStore parallel = new CountingStore();
        var router = new RoutingTargetE2eIntakeProposalStore(minio, parallel);

        router.resolve(pointer(URI));
        router.resolve(new ArtifactPointer(
                ARTIFACT_ID,
                "intake-turn-proposal.v2",
                "minio://target-e2e/proposals/" + HASH + ".json",
                HASH));

        assertThat(parallel.resolutions).hasValue(1);
        assertThat(minio.resolutions).hasValue(1);
        assertThatThrownBy(() -> router.resolve(pointer("https://example.invalid/proposal")))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .extracting(failure -> ((IntakeFinalizationRejectedException) failure).code())
                .isEqualTo("INTAKE_PROPOSAL_URI_FORBIDDEN");
    }

    private static ArtifactPointer pointer(String uri) {
        return new ArtifactPointer(ARTIFACT_ID, "intake-turn-proposal.v2", uri, HASH);
    }

    private static final class CountingStore implements TargetE2eIntakeProposalStore {
        private final AtomicInteger resolutions = new AtomicInteger();

        @Override
        public ProposalMetadata resolve(ArtifactPointer pointer) {
            resolutions.incrementAndGet();
            return new ProposalMetadata(
                    pointer.artifactId(),
                    pointer.schemaVersion(),
                    pointer.uri(),
                    pointer.sha256(),
                    pointer.sha256(),
                    2);
        }

        @Override
        public StoredProposal readExact(IntakeProposalReference reference) {
            return new StoredProposal(
                    reference.artifactId(),
                    reference.schemaVersion(),
                    reference.uri(),
                    reference.objectVersion(),
                    reference.sha256(),
                    reference.sizeBytes(),
                    new byte[] {'{', '}'});
        }
    }
}

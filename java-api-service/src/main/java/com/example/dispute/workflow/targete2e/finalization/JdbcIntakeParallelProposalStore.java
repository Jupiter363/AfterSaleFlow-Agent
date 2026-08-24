package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeProposalLoadException;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Immutable PostgreSQL reader for Java-assembled Intake proposals. */
public final class JdbcIntakeParallelProposalStore implements TargetE2eIntakeProposalStore {

    private static final String SCHEMA_VERSION = "intake-turn-proposal.v2";
    private static final String URI_PREFIX = "urn:target-e2e:proposal:intake:";
    private static final String LOAD =
            """
            select artifact_id, schema_version, artifact_uri, proposal_sha256,
                   size_bytes, canonical_proposal_bytes
              from intake_parallel_proposal_artifact
             where artifact_id = :artifactId
               and schema_version = :schemaVersion
               and artifact_uri = :uri
               and proposal_sha256 = :sha256
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIntakeParallelProposalStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public ProposalMetadata resolve(ArtifactPointer pointer) {
        Objects.requireNonNull(pointer, "pointer");
        requireCanonicalReference(
                pointer.artifactId(), pointer.schemaVersion(), pointer.uri(), pointer.sha256());
        ProposalRow row = loadExact(
                pointer.artifactId(), pointer.schemaVersion(), pointer.uri(), pointer.sha256());
        return new ProposalMetadata(
                row.artifactId(),
                row.schemaVersion(),
                row.uri(),
                row.sha256(),
                row.sha256(),
                row.sizeBytes());
    }

    @Override
    public StoredProposal readExact(IntakeProposalReference reference) {
        Objects.requireNonNull(reference, "reference");
        requireCanonicalReference(
                reference.artifactId(),
                reference.schemaVersion(),
                reference.uri(),
                reference.sha256());
        if (!reference.sha256().equals(reference.objectVersion())) {
            throw rejected(
                    "INTAKE_PROPOSAL_OBJECT_VERSION_MISMATCH",
                    "parallel proposal version must equal its content address");
        }
        ProposalRow row = loadExact(
                reference.artifactId(),
                reference.schemaVersion(),
                reference.uri(),
                reference.sha256());
        return new StoredProposal(
                row.artifactId(),
                row.schemaVersion(),
                row.uri(),
                row.sha256(),
                row.sha256(),
                row.sizeBytes(),
                row.payload());
    }

    private ProposalRow loadExact(
            String artifactId, String schemaVersion, String uri, String sha256) {
        List<ProposalRow> rows;
        try {
            rows = jdbc.query(
                    LOAD,
                    Map.of(
                            "artifactId", artifactId,
                            "schemaVersion", schemaVersion,
                            "uri", uri,
                            "sha256", sha256),
                    JdbcIntakeParallelProposalStore::mapRow);
        } catch (DataAccessException failure) {
            throw new IntakeProposalLoadException(
                    "parallel proposal object load failed", failure);
        }
        if (rows.isEmpty()) {
            throw rejected(
                    "INTAKE_PROPOSAL_OBJECT_NOT_FOUND",
                    "parallel proposal object was not found at its exact content address");
        }
        if (rows.size() != 1) {
            throw rejected(
                    "INTAKE_PROPOSAL_OBJECT_AMBIGUOUS",
                    "parallel proposal content address resolved to multiple objects");
        }
        return rows.getFirst();
    }

    private static ProposalRow mapRow(ResultSet resultSet, int ignored) throws SQLException {
        return new ProposalRow(
                resultSet.getString("artifact_id"),
                resultSet.getString("schema_version"),
                resultSet.getString("artifact_uri"),
                resultSet.getString("proposal_sha256"),
                resultSet.getLong("size_bytes"),
                resultSet.getBytes("canonical_proposal_bytes"));
    }

    private static void requireCanonicalReference(
            String artifactId, String schemaVersion, String uri, String sha256) {
        String expectedId = sha256 == null || sha256.length() < 32
                ? null
                : "intake.proposal." + sha256.substring(0, 32);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !Objects.equals(expectedId, artifactId)
                || !Objects.equals(URI_PREFIX + sha256, uri)) {
            throw rejected(
                    "INTAKE_PROPOSAL_URI_FORBIDDEN",
                    "parallel proposal reference is outside its canonical content address");
        }
    }

    private static IntakeFinalizationRejectedException rejected(String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }

    private record ProposalRow(
            String artifactId,
            String schemaVersion,
            String uri,
            String sha256,
            long sizeBytes,
            byte[] payload) {
        private ProposalRow {
            payload = payload == null ? null : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload == null ? null : payload.clone();
        }
    }
}

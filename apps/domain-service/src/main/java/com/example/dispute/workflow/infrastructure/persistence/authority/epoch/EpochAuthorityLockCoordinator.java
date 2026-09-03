package com.example.dispute.workflow.infrastructure.persistence.authority.epoch;

import com.example.dispute.workflow.application.authority.epoch.EpochAuthorityException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Coordinates every R1.5 acceptance/revocation lock with one stable table order.
 *
 * <p>Status is deliberately read after all rows are locked. This is the linearization boundary
 * shared by bootstrap, command acceptance and revocation writers.
 */
@Component
public final class EpochAuthorityLockCoordinator {

    private static final String ACCESS_SHARE_SQL =
            "select id, status from case_access_session "
                    + "where id in (:ids) order by id ASC FOR SHARE";
    private static final String AGENT_SHARE_SQL =
            "select id, status from agent_conversation_session "
                    + "where id in (:ids) order by id ASC FOR SHARE";
    private static final String REGISTRATION_SHARE_SQL =
            "select registration_id as id, registration_status as status "
                    + "from case_intake_graph_thread_binding "
                    + "where registration_id in (:ids) order by registration_id ASC FOR SHARE";
    private static final String ACCESS_UPDATE_SQL =
            "select id, status from case_access_session "
                    + "where id in (:ids) order by id ASC FOR UPDATE";
    private static final String AGENT_UPDATE_SQL =
            "select id, status from agent_conversation_session "
                    + "where id in (:ids) order by id ASC FOR UPDATE";
    private static final String REGISTRATION_UPDATE_SQL =
            "select registration_id as id, registration_status as status "
                    + "from case_intake_graph_thread_binding "
                    + "where registration_id in (:ids) order by registration_id ASC FOR UPDATE";

    private final NamedParameterJdbcTemplate jdbc;

    public EpochAuthorityLockCoordinator(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    public LockedRows lockForShare(LockRequest request) {
        return lock(request, false);
    }

    public LockedRows lockForUpdate(LockRequest request) {
        return lock(request, true);
    }

    public void requireActive(LockedRows rows) {
        Objects.requireNonNull(rows, "rows must not be null");
        requireStatus(rows.accessSessions(), "ACTIVE", "case access session");
        requireStatus(rows.agentSessions(), "ACTIVE", "Agent Session");
        requireStatus(rows.registrations(), "REGISTERED", "graph registration");
    }

    private LockedRows lock(LockRequest request, boolean forUpdate) {
        Objects.requireNonNull(request, "request must not be null");
        MapSqlParameterSource params = new MapSqlParameterSource("ids", request.accessSessionIds());
        // These calls are intentionally separate: PostgreSQL acquires each table's rows in the
        // declared order and each query orders rows by the stable primary identifier.
        List<LockedRow> access = jdbc.query(
                forUpdate ? ACCESS_UPDATE_SQL : ACCESS_SHARE_SQL,
                params,
                (resultSet, rowNum) -> new LockedRow(resultSet.getString("id"), resultSet.getString("status")));
        params = new MapSqlParameterSource("ids", request.agentSessionIds());
        List<LockedRow> agents = jdbc.query(
                forUpdate ? AGENT_UPDATE_SQL : AGENT_SHARE_SQL,
                params,
                (resultSet, rowNum) -> new LockedRow(resultSet.getString("id"), resultSet.getString("status")));
        params = new MapSqlParameterSource("ids", request.registrationIds());
        List<LockedRow> registrations = jdbc.query(
                forUpdate ? REGISTRATION_UPDATE_SQL : REGISTRATION_SHARE_SQL,
                params,
                (resultSet, rowNum) -> new LockedRow(resultSet.getString("id"), resultSet.getString("status")));
        requireExactRows(access, request.accessSessionIds(), "case access session");
        requireExactRows(agents, request.agentSessionIds(), "Agent Session");
        requireExactRows(registrations, request.registrationIds(), "graph registration");
        return new LockedRows(access, agents, registrations, forUpdate);
    }

    private static void requireExactRows(
            List<LockedRow> rows, Collection<String> requestedIds, String table) {
        Set<String> requested = new LinkedHashSet<>(requestedIds);
        Set<String> actual = new LinkedHashSet<>();
        for (LockedRow row : rows) {
            if (!actual.add(row.id())) {
                throw new EpochAuthorityException("AUTHORITY_DUPLICATE_ROW", table + " returned duplicate rows");
            }
        }
        if (!requested.equals(actual)) {
            throw new EpochAuthorityException(
                    "AUTHORITY_ROW_MISSING", table + " lock did not return the exact requested rows");
        }
    }

    private static void requireStatus(List<LockedRow> rows, String expected, String table) {
        for (LockedRow row : rows) {
            if (!expected.equals(row.status())) {
                throw new EpochAuthorityException(
                        "AUTHORITY_STATUS_REVOKED",
                        table + " " + row.id() + " is no longer " + expected);
            }
        }
    }

    public record LockRequest(
            List<String> accessSessionIds,
            List<String> agentSessionIds,
            List<String> registrationIds) {

        public LockRequest {
            accessSessionIds = normalized(accessSessionIds, "accessSessionIds");
            agentSessionIds = normalized(agentSessionIds, "agentSessionIds");
            registrationIds = normalized(registrationIds, "registrationIds");
        }

        private static List<String> normalized(Collection<String> values, String field) {
            Objects.requireNonNull(values, field + " must not be null");
            if (values.isEmpty()) {
                throw new IllegalArgumentException(field + " must not be empty");
            }
            List<String> result = values.stream()
                    .map(value -> {
                        if (value == null || value.isBlank()) {
                            throw new IllegalArgumentException(field + " contains a blank id");
                        }
                        return value;
                    })
                    .distinct()
                    .sorted(Comparator.naturalOrder())
                    .toList();
            if (result.size() != values.size()) {
                throw new IllegalArgumentException(field + " must contain unique ids");
            }
            return result;
        }
    }

    public record LockedRow(String id, String status) {
        public LockedRow {
            if (id == null || id.isBlank() || status == null || status.isBlank()) {
                throw new IllegalArgumentException("locked row id/status must not be blank");
            }
        }
    }

    public record LockedRows(
            List<LockedRow> accessSessions,
            List<LockedRow> agentSessions,
            List<LockedRow> registrations,
            boolean forUpdate) {
        public LockedRows {
            accessSessions = List.copyOf(accessSessions);
            agentSessions = List.copyOf(agentSessions);
            registrations = List.copyOf(registrations);
        }
    }
}

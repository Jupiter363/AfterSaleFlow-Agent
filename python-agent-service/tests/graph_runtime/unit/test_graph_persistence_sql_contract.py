from __future__ import annotations

from pathlib import Path
import re


SERVICE_ROOT = Path(__file__).resolve().parents[3]
MIGRATION_ROOT = SERVICE_ROOT / "migrations" / "graph"


def _sql(filename: str) -> str:
    return (MIGRATION_ROOT / filename).read_text(encoding="utf-8").lower()


def _compact(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def _constraint(sql_text: str, name: str) -> str:
    marker = f"constraint {name}"
    start = sql_text.index(marker)
    candidates = [
        position
        for position in (
            sql_text.find("\n    constraint ", start + len(marker)),
            sql_text.find("\n);", start + len(marker)),
        )
        if position >= 0
    ]
    return _compact(sql_text[start : min(candidates)])


def _function(sql_text: str, name: str) -> str:
    start = sql_text.index(f"create function {name}")
    end = sql_text.index("$function$;", start) + len("$function$;")
    return _compact(sql_text[start:end])


def _function_blocks(sql_text: str) -> tuple[str, ...]:
    return tuple(
        _compact(match.group(0))
        for match in re.finditer(
            r"create function\s+[a-z0-9_]+\([^)]*\).*?"
            r"as \$function\$.*?\$function\$;",
            sql_text,
            flags=re.DOTALL,
        )
    )


def test_command_ledger_freezes_the_six_states_and_legal_transitions() -> None:
    sql_text = _sql("G001_graph_runtime.sql")
    state_constraint = _constraint(sql_text, "ck_agent_graph_command_status")

    assert set(re.findall(r"'([a-z_]+)'", state_constraint)) == {
        "registered",
        "executing",
        "result_checkpointed",
        "completed",
        "cancelled",
        "aborted",
    }

    transition_guard = _function(sql_text, "guard_agent_graph_command_update()")
    assert (
        "old.status = 'registered' and "
        "new.status in ('executing', 'cancelled', 'aborted')" in transition_guard
    )
    assert (
        "old.status = 'executing' and "
        "new.status in ('result_checkpointed', 'cancelled', 'aborted')" in transition_guard
    )
    assert "old.status = 'result_checkpointed' and new.status = 'completed'" in transition_guard


def test_thread_scope_matches_wire_room_types_and_allows_private_hearing_threads() -> None:
    sql_text = _sql("G001_graph_runtime.sql")
    room_types = _constraint(sql_text, "ck_graph_thread_room_type")
    shared_scope = _constraint(sql_text, "ck_graph_thread_shared_scope")

    assert set(re.findall(r"'([a-z_]+)'", room_types)) == {
        "intake",
        "evidence",
        "hearing",
        "review",
    }
    assert "outcome" not in room_types
    assert "not shared_session or room_type = 'hearing'" in shared_scope


def test_result_checkpointed_and_completed_rows_have_the_complete_terminal_binding() -> None:
    sql_text = _sql("G001_graph_runtime.sql")
    binding = _constraint(sql_text, "ck_agent_graph_command_checkpoint_binding")

    assert "status not in ('result_checkpointed', 'completed')" in binding
    for required_fact in (
        "fencing_token is not null",
        "committed_checkpoint_ns is not null",
        "committed_checkpoint_id is not null",
        "result_ref is not null",
        "result_hash is not null",
        "result_checkpointed_at is not null",
    ):
        assert required_fact in binding

    terminal_time = _constraint(sql_text, "ck_agent_graph_command_terminal_time")
    assert "status = 'completed' and completed_at is not null" in terminal_time
    assert "status = 'cancelled' and cancelled_at is not null" in terminal_time
    assert "status = 'aborted' and aborted_at is not null" in terminal_time


def test_invocation_nonce_enforces_the_sixty_second_lifetime_and_retention() -> None:
    nonce_times = _constraint(
        _sql("G001_graph_runtime.sql"),
        "ck_agent_graph_nonce_times",
    )

    assert "token_expires_at <= issued_at + interval '60 seconds'" in nonce_times
    assert "65 seconds" not in nonce_times
    assert "retained_until >= issued_at + interval '24 hours'" in nonce_times


def test_lease_insert_starts_at_one_and_updates_keep_the_fence_monotonic() -> None:
    sql_text = _sql("G001_graph_runtime.sql")
    compact = _compact(sql_text)
    guard = _function(sql_text, "guard_agent_graph_lease_update()")

    assert re.search(r"before insert(?: or update)? on agent_graph_lease", compact)
    insert_guards = [
        block
        for block in _function_blocks(sql_text)
        if re.search(r"new\.fencing_token\s*(?:<>|!=)\s*1", block)
    ]
    assert insert_guards
    assert "new.fencing_token < old.fencing_token" in guard
    assert "new.fencing_token <> old.fencing_token + 1" in guard
    assert "only cancellation or eligible takeover may increment the fence" in guard
    lease_window = _constraint(sql_text, "ck_agent_graph_lease_window")
    assert "lease_expires_at <= renewed_at + interval '30 seconds'" in lease_window

    lease_upgrade = _compact(_sql("G009_graph_lease_sixty_second_window.sql"))
    assert "drop constraint ck_agent_graph_lease_window" in lease_upgrade
    assert "add constraint ck_agent_graph_lease_window" in lease_upgrade
    assert "lease_expires_at <= renewed_at + interval '60 seconds'" in lease_upgrade


def test_cancelled_released_and_expired_leases_allow_exactly_one_fenced_takeover() -> None:
    sql_text = _sql("G001_graph_runtime.sql")
    guard = _function(sql_text, "guard_agent_graph_lease_update()")

    for eligible_state in (
        "old.lease_expires_at <= clock_timestamp()",
        "old.cancelled_at is not null",
        "old.released_at is not null",
    ):
        assert eligible_state in guard
    for cleared_state in (
        "new.cancelled_at is null",
        "new.cancelled_by_command_id is null",
        "new.released_at is null",
    ):
        assert cleared_state in guard
    assert "new.fencing_token = old.fencing_token + 1" in guard
    assert "identity_changed and not taking_over" in guard
    assert "not taking_over and new.acquired_at <> old.acquired_at" in guard


def test_nonce_retention_has_an_expiry_guarded_cleanup_path() -> None:
    sql_text = _sql("G001_graph_runtime.sql")
    guard = _function(sql_text, "guard_agent_graph_nonce_delete()")

    assert "old.retained_until > statement_timestamp()" in guard
    assert "before delete on agent_graph_invocation_nonce" in _compact(sql_text)
    assert "before update on agent_graph_invocation_nonce" in _compact(sql_text)

    migration_source = (
        (SERVICE_ROOT / "app" / "graph_runtime" / "migrations.py")
        .read_text(encoding="utf-8")
        .lower()
    )
    assert "grant select, delete on {}.agent_graph_invocation_nonce to {}" in migration_source
    assert "grant select, insert on {}.agent_graph_invocation_nonce to {}" in migration_source
    assert 'agent_graph_invocation_nonce",\n        )' not in migration_source
    readiness_source = (
        (SERVICE_ROOT / "app" / "graph_runtime" / "readiness.py")
        .read_text(encoding="utf-8")
        .lower()
    )
    assert "runtime_delete_forbidden_relations" in readiness_source
    assert "runtime_append_only_relations" in readiness_source
    assert "can_mutate_append_only" in readiness_source


def test_checkpoint_restore_probe_binds_full_command_and_manifest_identity() -> None:
    readiness_source = (
        (SERVICE_ROOT / "app" / "graph_runtime" / "readiness.py")
        .read_text(encoding="utf-8")
        .lower()
    )

    for metadata_key in (
        "graph_thread_id",
        "graph_command_id",
        "graph_request_hash",
        "graph_room_epoch",
        "graph_key",
        "graph_version",
        "graph_checkpoint_schema_version",
        "graph_fencing_token",
        "graph_result_hash",
        "graph_result_ref",
    ):
        assert f"checkpoint.metadata ->> '{metadata_key}'" in readiness_source


def test_attempt_identity_state_and_provider_count_are_forward_only() -> None:
    sql_text = _sql("G001_graph_runtime.sql")
    guard = _function(sql_text, "guard_agent_graph_attempt_update()")

    for required in (
        "graph command attempt identity is immutable",
        "illegal graph attempt transition",
        "provider call count cannot decrease",
        "attempt heartbeat cannot move backwards",
    ):
        assert required in guard
    assert "before update on agent_graph_command_attempt" in _compact(sql_text)


def test_registry_shadow_is_loadable_and_retirement_is_reference_guarded() -> None:
    sql_text = _sql("G002_graph_version_registry.sql")
    states = _constraint(sql_text, "ck_agent_graph_registry_state")
    assert set(re.findall(r"'([a-z_]+)'", states)) == {
        "disabled",
        "shadow",
        "retired",
    }

    times = _constraint(sql_text, "ck_agent_graph_registry_times")
    shadow_branch = times.split("or (registry_state = 'retired'", 1)[0]
    assert re.search(r"registry_state\s*=\s*'shadow'.*\band loadable\b", shadow_branch)

    reference_view = _compact(
        sql_text[
            sql_text.index("create view agent_graph_version_active_reference") : sql_text.index(
                "create function guard_agent_graph_version_update()"
            )
        ]
    )
    for required_reference in (
        "thread.lifecycle_status",
        "command.status",
        "checkpoints",
        "agent_graph_shadow_comparison",
    ):
        assert required_reference in reference_view

    guard = _function(sql_text, "guard_agent_graph_version_update()")
    reference_counter = _function(sql_text, "agent_graph_shadow_comparison_reference_count(")
    assert "set search_path from current" in reference_counter
    assert "set search_path from current" in guard
    assert "new.registry_state = 'retired'" in guard
    assert "referenced graph version must remain loadable" in guard
    for reference_count in (
        "thread_count",
        "command_count",
        "result_count",
        "checkpoint_count",
        "shadow_comparison_count",
    ):
        assert f"active_reference.{reference_count} > 0" in guard
    assert "before delete on agent_graph_version_registry" in _compact(sql_text)


def test_shadow_comparison_delete_is_expiry_and_evidence_guarded_with_a_receipt() -> None:
    sql_text = _sql("G003_shadow_comparison.sql")
    compact = _compact(sql_text)

    cleanup_guards = [
        block
        for block in _function_blocks(sql_text)
        if "expires_at" in block
        and "evidence_manifest_ref" in block
        and "insert into agent_graph_shadow_cleanup_receipt" in block
    ]
    assert cleanup_guards
    assert "before delete on agent_graph_shadow_comparison" in compact or (
        "revoke delete on agent_graph_shadow_comparison" in compact
        and any("delete from agent_graph_shadow_comparison" in block for block in cleanup_guards)
    )
    assert "before update or delete on agent_graph_shadow_cleanup_receipt" in compact
    delete_guard = _function(sql_text, "guard_agent_graph_shadow_delete()")
    assert "security definer set search_path from current" in delete_guard
    assert "old.expires_at, session_user" in delete_guard

    migration_source = (
        (SERVICE_ROOT / "app" / "graph_runtime" / "migrations.py")
        .read_text(encoding="utf-8")
        .lower()
    )
    assert "grant select on {}.agent_graph_shadow_cleanup_receipt to {}" in migration_source
    assert "grant select, insert on {}.agent_graph_shadow_cleanup_receipt" not in migration_source


def test_shadow_comparison_identity_is_bound_to_the_command_and_registry_version() -> None:
    sql_text = _sql("G003_shadow_comparison.sql")
    compact = _compact(sql_text)

    assert "checkpoint_schema_version varchar(128) not null" in compact
    identity_bindings = (
        "tenant_surrogate",
        "case_id",
        "thread_id",
        "command_id",
        "graph_key",
        "graph_version",
        "checkpoint_schema_version",
    )
    identity_guards = [
        block
        for block in _function_blocks(sql_text)
        if "graph_thread_registry" in block
        and "agent_graph_command" in block
        and all(binding in block for binding in identity_bindings)
    ]
    composite_foreign_key = bool(
        re.search(
            r"foreign key\s*\([^)]*tenant_surrogate[^)]*case_id[^)]*"
            r"thread_id[^)]*command_id[^)]*graph_key[^)]*graph_version[^)]*"
            r"checkpoint_schema_version[^)]*\)",
            compact,
        )
    )
    assert composite_foreign_key or identity_guards
    if identity_guards:
        assert "before insert on agent_graph_shadow_comparison" in compact
        assert all("set search_path from current" in guard for guard in identity_guards)


def test_privileged_database_sessions_put_temporary_objects_last() -> None:
    migration_source = (
        (SERVICE_ROOT / "app" / "graph_runtime" / "migrations.py")
        .read_text(encoding="utf-8")
        .lower()
    )
    restore_source = (
        (SERVICE_ROOT / "app" / "graph_runtime" / "restore_validation.py")
        .read_text(encoding="utf-8")
        .lower()
    )

    assert "set search_path to {}, pg_catalog, pg_temp" in migration_source
    assert "set search_path to {}, pg_catalog, pg_temp" in restore_source

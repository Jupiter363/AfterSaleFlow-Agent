-- Receipt lineage is command-attempt scoped; the lease fencing token is thread scoped.
-- A valid first receipt can therefore begin at any positive current thread fence.

alter table agent_graph_parallel_receipt_execution
    drop constraint ck_agent_graph_parallel_execution_fence;

alter table agent_graph_parallel_receipt_execution
    add constraint ck_agent_graph_parallel_execution_fence
        check (
            fencing_token >= 1
            and provider_call_count_at_admission >= 0
            and not (
                predecessor_cycle_id is not null
                and predecessor_execution_id is not null
            )
        );

create or replace function guard_agent_graph_parallel_execution_insert()
returns trigger
language plpgsql
as $function$
begin
    if not require_parallel_intake_graph_command(
        new.thread_id, new.command_id, new.request_hash
    ) or not exists (
        select 1
          from agent_graph_command command
          join agent_graph_command_attempt attempt
            on attempt.thread_id = command.thread_id
           and attempt.command_id = command.command_id
          join agent_graph_lease lease
            on lease.thread_id = command.thread_id
           and lease.command_id = command.command_id
         where command.thread_id = new.thread_id
           and command.command_id = new.command_id
           and command.status = 'EXECUTING'
           and command.fencing_token = new.fencing_token
           and attempt.attempt_id = new.attempt_id
           and attempt.attempt_status = 'EXECUTING'
           and lease.owner_id = new.owner_id
           and lease.fencing_token = new.fencing_token
           and lease.released_at is null
           and lease.cancelled_at is null
           and lease.lease_expires_at > clock_timestamp()
           and (
               (
                   new.predecessor_cycle_id is null
                   and new.predecessor_execution_id is null
                   and attempt.fencing_token = new.fencing_token
                   and new.provider_call_count_at_admission = 0
                   and attempt.provider_call_count = 0
                   and not exists (
                       select 1
                         from agent_graph_parallel_receipt_execution prior
                        where prior.attempt_id = new.attempt_id
                   )
                   and not exists (
                       select 1
                         from agent_graph_parallel_receipt_cycle prior
                        where prior.attempt_id = new.attempt_id
                   )
               )
               or (
                   new.predecessor_cycle_id is not null
                   and new.predecessor_execution_id is null
                   and attempt.fencing_token = new.fencing_token - 1
                   and new.provider_call_count_at_admission
                       = attempt.provider_call_count
                   and exists (
                       select 1
                         from agent_graph_parallel_receipt_cycle prior
                        where prior.cycle_id = new.predecessor_cycle_id
                          and prior.attempt_id = new.attempt_id
                          and prior.thread_id = new.thread_id
                          and prior.command_id = new.command_id
                          and prior.frame_set_id = new.frame_set_id
                          and prior.authority_sha256 = new.authority_sha256
                          and prior.receipt_sha256 <> new.receipt_sha256
                          and prior.fencing_token = new.fencing_token - 1
                          and prior.provider_call_count_after
                              = attempt.provider_call_count
                          and not exists (
                              select 1
                                from agent_graph_parallel_receipt_cycle newer
                               where newer.attempt_id = prior.attempt_id
                                 and newer.fencing_token > prior.fencing_token
                          )
                   )
               )
               or (
                   new.predecessor_cycle_id is null
                   and new.predecessor_execution_id is not null
                   and attempt.fencing_token = new.fencing_token - 1
                   and new.provider_call_count_at_admission
                       = attempt.provider_call_count
                   and exists (
                       select 1
                         from agent_graph_parallel_receipt_execution predecessor
                        where predecessor.execution_id
                            = new.predecessor_execution_id
                          and predecessor.attempt_id = new.attempt_id
                          and predecessor.thread_id = new.thread_id
                          and predecessor.command_id = new.command_id
                          and predecessor.frame_set_id = new.frame_set_id
                          and predecessor.receipt_sha256 = new.receipt_sha256
                          and predecessor.authority_sha256 = new.authority_sha256
                          and predecessor.owner_id = attempt.owner_id
                          and predecessor.fencing_token = new.fencing_token - 1
                          and predecessor.provider_call_count_at_admission
                              = attempt.provider_call_count
                          and not exists (
                              select 1
                                from agent_graph_parallel_receipt_execution newer
                               where newer.attempt_id = predecessor.attempt_id
                                 and newer.receipt_sha256
                                     = predecessor.receipt_sha256
                                 and newer.fencing_token
                                     > predecessor.fencing_token
                          )
                          and not exists (
                              select 1
                                from agent_graph_parallel_receipt_cycle completed
                               where completed.attempt_id = predecessor.attempt_id
                                 and completed.receipt_sha256
                                     = predecessor.receipt_sha256
                          )
                   )
               )
           )
    ) then
        raise exception using errcode = '23514',
            message = 'parallel receipt execution authority is invalid';
    end if;
    return new;
end;
$function$;

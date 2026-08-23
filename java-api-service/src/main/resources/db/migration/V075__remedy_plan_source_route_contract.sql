-- The V2 Hearing handoff is a FULL_HEARING route. HEARING_V2 is an artifact
-- protocol label, not a value in the authoritative RouteType domain contract.

update remedy_plan
   set source_route = 'FULL_HEARING',
       updated_at = now(),
       updated_by = 'flyway-v075'
 where source_route = 'HEARING_V2';

alter table remedy_plan
    add constraint ck_remedy_plan_source_route
        check (source_route in ('TRANSFERRED', 'SIMPLE_HEARING', 'FULL_HEARING'));

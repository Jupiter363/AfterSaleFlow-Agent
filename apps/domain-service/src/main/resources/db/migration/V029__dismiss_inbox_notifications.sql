alter table notification
    add column dismissed_at timestamptz;

drop index if exists idx_notification_recipient_unread;

create index idx_notification_recipient_unread
    on notification(recipient_id, created_at desc)
    where read_at is null and dismissed_at is null;

create index idx_notification_recipient_visible
    on notification(recipient_id, created_at desc)
    where dismissed_at is null;

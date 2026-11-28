-- Issue #10: the company's website domain (e.g. stripe.com), used to show its logo on alerts.
-- Nullable: boards added without one simply get alerts without a logo.
alter table boards add column domain text check (domain is null or domain ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$');

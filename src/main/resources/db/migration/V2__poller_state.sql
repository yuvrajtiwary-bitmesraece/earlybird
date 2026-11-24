-- Poller state (Block 2). V1 is already applied, so changes go in a new migration; editing V1 would fail
-- Flyway's checksum validation on every database that ran it.

-- Jobs found on a board's FIRST successful check already existed before we started watching, so they are
-- not "new": they are stored (so later checks can detect closures) but never alerted instantly. Without
-- this, adding ~150 boards would look like thousands of new jobs, flooding users and Gemini's daily quota.
alter table postings add column baseline boolean not null default false;

-- A board that suddenly returns 0 jobs is more likely broken than empty. Counting such responses lets the
-- poller ignore up to 2 in a row and only believe the third.
alter table boards add column consecutive_empty_checks integer not null default 0
    check (consecutive_empty_checks >= 0);

-- ETag from the board's last full response. Sent back as If-None-Match: an unchanged board then answers
-- 304 Not Modified with no body, so there is nothing to download or parse.
alter table boards add column etag text;

-- EarlyBird schema v1 (docs/earlybird-spec.md, section 3.4).
-- Runs inside the schema named by DB_SCHEMA (Flyway creates it and sets search_path), so no table is
-- schema-qualified here. All timestamps are timestamptz and the app always writes UTC.
-- Enum-like columns are text with CHECK constraints that mirror the Java enums; a test inserts every
-- Java enum value to prove the two lists agree.

-- Careers boards we poll ------------------------------------------------------------------------------
create table boards (
    id                   bigint generated always as identity primary key,
    source               text        not null check (source in ('greenhouse', 'lever', 'ashby')),
    token                text        not null,
    company              text        not null,
    enabled              boolean     not null default true,
    check_interval_s     integer     not null default 180 check (check_interval_s > 0),
    next_check_at        timestamptz not null default now(),
    last_checked_at      timestamptz,
    last_success_at      timestamptz,
    consecutive_failures integer     not null default 0 check (consecutive_failures >= 0),
    last_error           text,
    last_job_count       integer,
    created_at           timestamptz not null default now(),
    constraint boards_source_token_uq unique (source, token)
);
-- The poller asks "which enabled boards are due?"; a partial index skips disabled boards entirely.
create index boards_due_idx on boards (next_check_at) where enabled;

-- Job postings seen on those boards --------------------------------------------------------------------
create table postings (
    id                  uuid        primary key,  -- UUID v7, created by the app
    board_id            bigint      not null references boards (id),
    source              text        not null check (source in ('greenhouse', 'lever', 'ashby')),
    external_id         text        not null,
    url                 text        not null,
    title               text        not null,
    company             text        not null,
    location_raw        text,
    departments         text[]      not null default '{}',
    source_published_at timestamptz,          -- only trusted for some sources (see README)
    first_seen_at       timestamptz not null,
    last_seen_at        timestamptz not null,
    missing_count       integer     not null default 0 check (missing_count >= 0),
    closed_at           timestamptz,
    content_hash        text        not null,
    description_text    text,                 -- set to null once tagged (copyright, storage)
    is_repost           boolean     not null default false,
    event_published_at  timestamptz,          -- null = not yet on the event bus (outbox-lite relay)
    -- The board's own id for the job: inserting the same job twice is impossible.
    constraint postings_board_external_uq unique (board_id, external_id)
);
create index postings_first_seen_idx on postings (first_seen_at desc);
create index postings_content_hash_idx on postings (content_hash);
-- The outbox relay looks only for unpublished rows, which should be few.
create index postings_unpublished_idx on postings (first_seen_at) where event_published_at is null;

-- What Gemini (or the fallback tagger) extracted from a posting -----------------------------------------
create table posting_tags (
    posting_id       uuid        primary key references postings (id) on delete cascade,
    role_category    text        not null check (role_category in (
                         'backend', 'frontend', 'fullstack', 'mobile', 'data_engineering', 'data_science_ml',
                         'devops_sre_cloud', 'qa_test', 'security', 'embedded', 'other')),
    seniority        text        not null check (seniority in (
                         'intern', 'new_grad', 'junior', 'mid', 'senior', 'staff_plus', 'unknown')),
    min_years        numeric(4, 1) check (min_years >= 0),
    max_years        numeric(4, 1) check (max_years >= 0),
    fresher_friendly boolean     not null default false,
    employment_type  text        not null check (employment_type in (
                         'full_time', 'internship', 'contract', 'part_time', 'unknown')),
    work_mode        text        not null check (work_mode in ('remote', 'hybrid', 'onsite', 'unknown')),
    remote_scope     text        not null check (remote_scope in ('worldwide', 'country', 'region', 'unknown')),
    cities           text[]      not null default '{}',
    countries        text[]      not null default '{}',  -- ISO 3166 alpha-2
    skills           text[]      not null default '{}',
    summary          text,
    tagger           text        not null check (tagger in ('gemini', 'fallback')),
    model            text,
    enriched_at      timestamptz not null
);
-- GIN indexes answer "arrays containing X" (skills @> '{Kafka}') without scanning every row.
create index posting_tags_skills_gin on posting_tags using gin (skills);
create index posting_tags_role_idx on posting_tags (role_category);

-- Telegram users --------------------------------------------------------------------------------------
create table users (
    id               bigint generated always as identity primary key,
    telegram_user_id bigint      not null,
    chat_id          bigint      not null,
    first_name       text,
    username         text,
    status           text        not null default 'onboarding'
                         check (status in ('onboarding', 'active', 'paused', 'blocked')),
    consent_at       timestamptz,
    created_at       timestamptz not null default now(),
    last_active_at   timestamptz,
    constraint users_telegram_user_uq unique (telegram_user_id)
);
create index users_status_idx on users (status);

-- Preferences and extracted resume facts (never the resume itself) --------------------------------------
create table profiles (
    user_id             bigint       primary key references users (id) on delete cascade,
    roles               text[]       not null default '{}',  -- role_category values
    cities              text[]       not null default '{}',
    remote_india        boolean      not null default false,
    remote_worldwide    boolean      not null default false,
    years_experience    numeric(3, 1) not null default 0 check (years_experience >= 0),
    include_internships boolean      not null default false,
    skills              text[]       not null default '{}',
    suggested_roles     text[]       not null default '{}',  -- from resume parsing (spec 3.8)
    graduation_year     smallint,
    min_score           smallint     not null default 55 check (min_score between 0 and 100),
    daily_cap           smallint     not null default 10 check (daily_cap between 0 and 100),
    resume_sha256       text,
    resume_uploaded_at  timestamptz,
    updated_at          timestamptz  not null default now()
);
-- The matcher's candidate query starts with "users whose roles include this posting's category".
create index profiles_roles_gin on profiles using gin (roles);

-- Onboarding / conversation state for the bot -----------------------------------------------------------
create table bot_sessions (
    user_id    bigint      primary key references users (id) on delete cascade,
    state      text        not null,
    data       jsonb       not null default '{}'::jsonb,
    updated_at timestamptz not null default now()
);

-- One row per (user, posting) we decided to tell the user about -----------------------------------------
create table alerts (
    id                  uuid        primary key,  -- UUID v7
    user_id             bigint      not null references users (id) on delete cascade,
    posting_id          uuid        not null references postings (id) on delete cascade,
    score               smallint    not null check (score between 0 and 100),
    matched_skills      text[]      not null default '{}',
    missing_skills      text[]      not null default '{}',
    mode                text        not null check (mode in ('instant', 'digest')),
    status              text        not null default 'queued'
                            check (status in ('queued', 'sending', 'sent', 'failed', 'skipped', 'cancelled')),
    created_at          timestamptz not null default now(),
    sent_at             timestamptz,
    telegram_message_id bigint,
    feedback            text check (feedback in ('relevant', 'not_relevant')),
    feedback_at         timestamptz,
    -- The duplicate-alert guarantee: the database refuses a second alert for the same user and posting,
    -- however many times an event is redelivered or a tick re-runs.
    constraint alerts_user_posting_uq unique (user_id, posting_id)
);
create index alerts_status_created_idx on alerts (status, created_at);
-- Closing a posting cancels its queued alerts; also keeps the cascade delete from postings fast.
create index alerts_posting_idx on alerts (posting_id);

-- Idempotent consumers: a row per (event, consumer) written in the consumer's own transaction --------------
create table processed_events (
    event_id     uuid        not null,
    consumer     text        not null,
    processed_at timestamptz not null default now(),
    primary key (event_id, consumer)
);
create index processed_events_processed_at_idx on processed_events (processed_at);

-- Telegram may deliver the same webhook update more than once ----------------------------------------------
create table telegram_updates (
    update_id   bigint      primary key,
    received_at timestamptz not null default now()
);
create index telegram_updates_received_at_idx on telegram_updates (received_at);

-- One row per poller tick, for /status and /admin ---------------------------------------------------------
create table ticks (
    id              bigint      generated always as identity primary key,
    started_at      timestamptz not null,
    finished_at     timestamptz,
    boards_checked  integer     not null default 0,
    boards_failed   integer     not null default 0,
    new_postings    integer     not null default 0,
    closed_postings integer     not null default 0,
    status          text        not null default 'running'
                        check (status in ('running', 'ok', 'partial', 'failed'))
);
create index ticks_started_at_idx on ticks (started_at desc);

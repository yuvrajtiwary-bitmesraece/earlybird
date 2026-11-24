-- Gemini calls made per quota day (Block 3). Google's free-tier daily quota resets at midnight Pacific
-- time, so "day" is the date in America/Los_Angeles, not UTC or IST. This is the durable counter; in
-- Block 4 Redis becomes the fast primary and this table its fallback (spec section 3.7).
create table gemini_usage (
    day   date    primary key,
    calls integer not null default 0 check (calls >= 0)
);

-- Block 5: the outbox relay (spec 3.2, "outbox-lite").
--
-- postings.event_published_at already covers PostingDiscovered. This adds the same for PostingEnriched: without
-- it, a posting tagged while Kafka is down would never reach the matcher, because its description (the tagging
-- queue) is already gone.
alter table posting_tags add column event_published_at timestamptz;

-- Everything tagged before this migration was already published, on the in-process bus.
update posting_tags set event_published_at = enriched_at;

-- The relay's queries look only at unpublished rows, which are few: keep those in small partial indexes.
create index posting_tags_unpublished_idx on posting_tags (enriched_at) where event_published_at is null;

-- V1's index covered every posting with event_published_at null, and that turned out to be most of them:
-- baseline postings are never published by design (16,000+ after the first tick). The relay only wants
-- postings still waiting to be tagged, so the index now holds just those.
drop index postings_unpublished_idx;
create index postings_unpublished_idx on postings (first_seen_at)
    where event_published_at is null and description_text is not null;

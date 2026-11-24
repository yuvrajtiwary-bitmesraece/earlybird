-- Tagging work queue (Block 4). A posting needs tagging while it still has description_text: the poller
-- stores a description only for postings that should be tagged, and tagging clears it. So the postings table
-- itself is the queue, and an event that goes missing can't lose work: the next sweep finds the posting.
--
-- tag_claimed_at is a lease: a worker claims up to 15 rows, calls Gemini outside any transaction, and writes
-- the tags. If it dies, the lease runs out and another sweep picks the rows up again.
alter table postings add column tag_claimed_at timestamptz;

create index postings_untagged_idx on postings (first_seen_at) where description_text is not null;

-- Digest: "queued digest alerts from the last 24 hours, per user".
create index alerts_digest_idx on alerts (user_id, created_at) where mode = 'digest' and status = 'queued';

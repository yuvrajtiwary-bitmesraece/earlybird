# EarlyBird — interview notes

One section per build block: what was built, why, the alternatives, what breaks first under load, and likely interview questions.

---

## Block 0 — Accounts, keys and repo (2026-11-24)

### What was built
- Public GitHub repo with the spec, `.env.example` (every config variable, no values), MIT license and a `.gitignore` that blocks `.env` and `.env.*`. Secret scanning and push protection are on.
- Accounts on free tiers, no credit card: two Telegram bots (prod `@EarlyBirdJobsBot`, dev `@EarlyBirdDevYuvrajBot`), a Gemini API key in its own Google Cloud project, Supabase Postgres in Singapore, Upstash Redis in Singapore, Upstash QStash (EU region).
- Every credential was checked with a live call that prints only OK/FAIL: Telegram `getMe`, Gemini model list plus one `generateContent`, a Postgres login, Redis `AUTH` + `PING` over TLS, and a QStash API call.

### Why this design
- **Everything the app talks to sits next to the app.** Render's closest region to India is Singapore, so Postgres and Redis are in Singapore too. Users never talk to the database; the app does, hundreds of times per 3-minute tick, so app↔database latency is what matters. Tokyo or Mumbai would add roughly 60–70 ms to every query.
- **Region is permanent, so it was fixed early.** The first Supabase project landed in Tokyo because the form defaulted to "Auto". It was empty, so it was deleted and recreated in Singapore by choosing the exact region.
- **Session pooler, not transaction pooler.** It works over IPv4 (Supabase's direct connection is IPv6-only on the free plan), keeps prepared statements, and supports session-level advisory locks, which Flyway's migration lock and our Postgres lock fallback rely on.
- **Two bots.** A bot can use a webhook or `getUpdates` polling, never both. Production uses a webhook; local development polls with a separate dev bot, so local testing never steals production updates.
- **Gemini gets its own Google Cloud project**, because rate limits are per project, not per key.
- **The model was chosen from measured limits, not assumed ones.** Listing the live models and reading AI Studio's rate-limit page for this project gave: Gemini 3.5 Flash-Lite at 15 requests/min, 250K tokens/min and 500 requests/day; Gemini 3.8 Flash at only 20 requests/day. The spec's daily budget of 400 calls fits under 500 with headroom. The ID is pinned (`gemini-3.5-flash-lite`) rather than using the `flash-lite-latest` alias, so tagging output can't change under us without a commit.
- **Secrets never enter git or the terminal.** Only `.env.example` is committed. The webhook secret and metrics token were generated with `openssl rand -hex 32` straight into the gitignored file. The env files are read as Java properties, so they have no inline comments and no quotes (a properties file keeps both as part of the value; this actually bit us when Upstash's copy button added quotes).

### Alternatives considered
| Option | Why not |
|---|---|
| Supabase in Mumbai (closest to users) | The app, not the users, talks to the database, and Render has no Mumbai region |
| Supabase transaction pooler (port 6543) | Breaks session-level advisory locks; needs `prepareThreshold=0` |
| `gemini-flash-lite-latest` alias | Auto-upgrades silently; tagging results could drift between runs |
| Gemini 3.8 Flash | Better model, but 20 requests/day is unusable for batch tagging |
| QStash US region | Irrelevant for a cron that fires every 3 minutes; EU is the default |

### What breaks first
- **At 10×:**
  - **Gemini's 500 requests/day.** At up to 15 postings per call, 400 calls tag at most about 6,000 postings a day. More boards, or a big first import of existing postings, run past that, and the deterministic fallback tagger takes over at lower quality.
  - **Upstash's 500K commands/month.** The spec estimates about 180K/month at current scale, and most commands scale with alerts sent (one rate-limiter `EVAL` per message), so 10× alerts is about 1.8M.
- **At 100×:**
  - Render's 0.1 CPU and 512 MB.
  - Supabase's 500 MB storage.
  - Telegram's ~30 messages/s global limit.
  - The answer here is paid tiers or the Oracle Cloud + Kubernetes path in `deploy/`.

### Likely interview questions
1. **"Your users are in India. Why is your database in Singapore?"**
   Because the database's client is the app, not the user. Render's nearest region is Singapore, and each tick makes hundreds of queries, so app↔database latency dominates. Users reach the app through Telegram, so their distance to the database never matters. I also recreated the first project when it landed in Tokyo, because Supabase regions can't be changed later.
2. **"Why the session pooler instead of the transaction pooler?"**
   The transaction pooler hands a connection back after every transaction. That breaks session-level advisory locks, which Flyway uses during migrations and which I use as a fallback lock, and it forces prepared statements off. With a Hikari pool of 5, I don't need the transaction pooler's higher connection count.
3. **"How do you keep secrets out of a public repo?"**
   Several layers:
   - Secrets live only in gitignored env files locally and in Render/GitHub secrets in production.
   - Only a values-free `.env.example` is committed, and I check `git check-ignore` before the first commit.
   - GitHub secret scanning and push protection are on.
   - Generated secrets go straight from `openssl` into the file without being displayed.
   - My verification scripts print OK/FAIL, never values.

---

## Block 1 — Project skeleton (2026-11-24)

### What was built
- Spring Boot 4.2.0 on Java 25, built with the Maven wrapper (Maven 3.10.1), so a fresh machine needs only a JDK.
- **Roles:** `@ConditionalOnRole(Role.POLLER)` turns a configuration class or bean on only when that role is in `EARLYBIRD_ROLES`.
- **Typed config:** one `EarlyBirdProperties` record bound from the spec's environment variables, validated at startup.
- **Schema:** Flyway V1 with all 10 tables from the spec; one `JdbcClient` repository per table.
- **Interfaces with in-memory implementations:** `EventBus`, `DistributedLock` and `RateLimiter`. Kafka and Redis versions come later.
- **Health:** liveness and readiness endpoints.
- **CI:** a GitHub Actions workflow (moved forward from Block 8) that runs every test, including the Docker-based ones.
- **Verified:** the packaged app started against the Supabase dev schema, applied V1, and reported ready. Restart in 7.3 s; 231 MB resident memory.

### Why this design
- **Roles instead of separate services.** One codebase and one image. On Render's single free container every role runs together; the Helm chart later gives each role its own Deployment. `@ConditionalOnRole` is a custom Spring `Condition` that reads `EARLYBIRD_ROLES` from the `Environment`, because conditions run before any bean (including the properties bean) exists.
- **Config fails fast, with every problem at once.** The record's constructor checks everything and throws one message listing each problem by its environment-variable name ("GEMINI_API_KEY is required when roles include enricher or bot"). Requirements depend on roles, so a web-only pod doesn't need a Gemini key. `toString()` masks secrets, so logging the config can't leak them.
- **`JdbcClient` and plain SQL, no JPA.** Less startup time and memory on 0.1 CPU, and every query is visible and explainable. Postgres features the app relies on (arrays, `jsonb`, `on conflict do nothing`, partial and GIN indexes) are first-class in SQL and awkward through an ORM.
- **The database enforces the invariants.** Duplicates are prevented by unique constraints, not by "check then insert" code, which races. `insert … on conflict do nothing` returns whether the row was new, so callers learn "already seen" in one round trip. Enum columns are `text` with CHECK constraints, and a test inserts every Java enum value so the two lists can't drift apart.
- **UUID v7 for postings and alerts.** Time-ordered, so index inserts stay at the right-hand edge like a sequence, but the app can create ids without asking the database (it needs the id before the event is published).
- **The in-memory event bus mimics Kafka's model**: a key picks one of two lanes (like two partitions) and each lane handles one event at a time, so one user's alerts stay in order locally just as they will in Kafka.
- **Lease-based lock.** A lock that expires on its own means a crashed tick can't block every later tick. Release is compare-and-delete, so a holder whose lease expired can't release someone else's lock.
- **Token bucket refilled lazily.** No background thread: each call computes the refill from elapsed time, inside `ConcurrentHashMap.compute`, which is atomic per key. A test runs 64 threads against a 25-token bucket and gets exactly 25 grants. A unit test also caught a real floating-point bug (1 − 0.999 rounded up to 2 ms instead of 1 ms).
- **Liveness ≠ readiness.** Readiness includes the database (don't send traffic if the database is down); liveness doesn't (a database outage shouldn't make the platform restart a healthy app in a loop).
- **Tests that need Docker skip on the laptop but can't silently skip in CI.** `@Testcontainers(disabledWithoutDocker = true)` keeps `./mvnw verify` green with only a JDK; a CI step fails the build if any integration test was skipped.

### Alternatives considered
| Option | Why not |
|---|---|
| JPA / Hibernate | Slower startup and more memory on 0.1 CPU; hides SQL; weak fit for arrays, `jsonb`, upserts |
| `@ConditionalOnProperty` per module | Works, but repeats a property expression everywhere; one annotation with a `Role` enum is type-safe and readable |
| Bean Validation annotations on the config | Can't express "required only if role X is enabled" cleanly, and reports one field at a time |
| H2 for tests | Different SQL dialect: no `text[]`, `jsonb` or the same `on conflict` behaviour, so tests would pass on SQL that fails in production |
| Embedded Postgres binaries instead of Testcontainers | Would run locally without Docker, but the spec standardises on Testcontainers, and CI has Docker |
| UUID v4 / `bigserial` | v4 scatters index inserts; `bigserial` needs a database round trip to learn the id |

### What breaks first
- **At 10×:** the in-memory lock and rate limiter assume one process. They're correct on Render's single instance, but a second instance would double the Telegram send rate and run ticks twice. That's why the Redis versions (Blocks 4 and 6) exist, with these as the fallback.
- **At 100×:**
  - Hikari's pool of 5 connections and Supabase's session-pooler connection limit; transaction pooling or a bigger database tier would be needed.
  - The per-chat rate-limit map grows by one small entry per chat. That's fine for 10K users, but at millions it would need idle-entry eviction.

### Likely interview questions
1. **"How does one codebase run as a monolith on Render and as microservices in Kubernetes?"**
   Modules never call each other directly; they publish and subscribe through an `EventBus` interface, which is Kafka in production. Each module's beans sit behind `@ConditionalOnRole`, and `EARLYBIRD_ROLES` picks which roles a process runs. On Render it's all of them in one container; in the Helm chart each Deployment sets a different subset. Same image, no code change.
2. **"How do you guarantee a user never gets the same alert twice?"**
   A unique constraint on `alerts (user_id, posting_id)`. The matcher inserts with `on conflict do nothing`, so a redelivered event or re-run tick is a no-op, and consumers also record `(event_id, consumer)` in `processed_events` in the same transaction. An integration test proves even raw SQL that bypasses the repository is rejected with a `DuplicateKeyException`.
3. **"Why UUID v7 instead of an auto-increment id or a random UUID?"**
   The poller needs the id before the row is committed, because it goes into the event, so a database sequence would cost a round trip. Random v4 UUIDs land all over the B-tree index, causing page splits and cache misses. v7 starts with a millisecond timestamp, so inserts append at the end of the index like a sequence and ids sort by creation time. A test checks 10,000 ids are strictly increasing.

---

## Block 2 — Job sources and the poller (2026-11-24)

### What was built
- **Clients:** Greenhouse, Lever and Ashby, sharing one HTTP client:
  - timeouts, gzip, at most 4 requests per host, retries for 5xx and timeouts
  - ETags (`If-None-Match` → `304`)
  - a 32 MB response cap
- **The differ:**
  - new jobs are inserted; seen jobs are refreshed; a job missing twice is closed and reopened if it returns
  - a board that suddenly lists 0 jobs is ignored twice, then believed
  - reposts are caught by content fingerprint
  - plus the **baseline rule** (below)
- **Seed list:** 130 boards, built by trying ~740 candidate companies on all three platforms and checking each board's identity and job locations. A `--validate-boards` command checks the list without starting Spring.
- **Ticks and events:** a tick service runs every 3 minutes (internal scheduler). Events are published only after the database commit.
- **Migration:** V2 adds `postings.baseline`, `boards.etag` and `boards.consecutive_empty_checks`.
- **Measured on live boards:**
  - baseline tick: 130 boards in 33 s, 16,336 jobs stored, **295** sent on for tagging, 0 failures, peak memory 321 MB
  - steady state: 17 s per tick, 129 of 130 boards answering `304`

### Why this design
- **Fixtures first.** Real responses showed things the spec couldn't know:
  - Lever has no publish time. Its `createdAt` is when the draft was made: a "new" CRED job said August.
  - Ashby titles carry stray spaces.
  - Greenhouse wraps company boilerplate in `content-intro` blocks.
  - Zeta's Lever description spends its first 3,000 characters on "About us", so the requirements would have been cut off.

  Each of these is now handled and pinned by a test against the real fixture.
- **The baseline rule.** The first check of a board sees every existing job as new: 16,336 here. Tagging them all is ~1,090 Gemini calls against a 500-a-day quota, and alerting on them would spam users with month-old jobs. Baseline jobs are stored (needed to detect closures) but not alerted. Only those a trustworthy source says were published in the last 48 h are tagged, for "recent matches" and the digest. The rule applies per board, so boards added later get the same treatment.
- **ETags turn most checks into a 304.** All three platforms support conditional GET. On 0.1 CPU the expensive part is parsing; an unchanged board now costs one small request and one `UPDATE`.
- **Streaming JSON.** OpenAI's Ashby board is 14 MB. Reading it into one tree needs several times that in heap on a 512 MB instance. Jobs are read one at a time from the stream and discarded after conversion, and boards over 10 MB were left out of the seed list anyway.
- **Don't trust a sudden zero.** A board that had 5+ jobs and suddenly lists none is more likely broken than empty. Closing everything would cancel every queued alert for that company. It's counted as a failure and only believed the third time in a row.
- **Close on the second miss; reopen on return.** One flaky response can't close a job, and a job that reappears is reopened without being re-announced.
- **Commit, then publish, then mark published.** Events go out only after the transaction commits, and `event_published_at` is set only after the bus accepts the event. A crash in between leaves a row with `event_published_at = null`, which the outbox relay (Block 5) republishes. Nothing is announced before it exists, and nothing is lost.
- **Reposts by fingerprint.** `sha256(company | title | location | first 1,000 chars)`. Stripping boilerplate matters here too: if every job's text began with the same "About us", the fingerprint would effectively be just company and title.
- **No Redis for de-duplication.** Postgres unique constraints decide, with `on conflict do nothing` (the spec forbids spending Redis commands on this).

### Alternatives considered
| Option | Why not |
|---|---|
| Always request Greenhouse with `content=true` | ~10× more data per check. Used only for a board's first check, or when more than 5 jobs are new at once |
| Treat Lever's `createdAt` as the publish time | It's the draft's creation time; latency numbers would be wrong, and old jobs could look recent |
| Parse each response into one JSON tree | Simple, but memory grows with the biggest board, and a 14 MB board is enough to hurt on 512 MB |
| Regex to strip HTML | Breaks on real markup (entities, nesting, scripts); Jsoup parses like a browser |
| Close a job the first time it's missing | One flaky response would cancel alerts |
| Skip the baseline and accept the first flood | Burns 2+ days of Gemini quota and alerts on stale jobs |

### What breaks first
- **At 10× (~1,300 boards):**
  - **Tick time.** At 4 requests per host and ~0.5 s per request to US-hosted APIs, 800 Greenhouse boards take ~100 s even when everything answers `304`. That's still under 3 minutes, but with no margin. The answer is the adaptive schedule already behind `ADAPTIVE_SCHEDULING` (busy boards every 3 minutes, quiet ones every 30).
  - **Baseline memory spikes** when many big boards are added at once.
- **At 100×:**
  - Per-host politeness limits and platform rate limits (429s) dominate.
  - Postgres write volume: an `UPDATE` per board per tick.
  - The answer is to shard boards across several poller pods. Each pod takes a disjoint set (for example `board_id % N`), which the roles design allows.

### Likely interview questions
1. **"When you add a new company, how do you avoid alerting users about all its existing jobs?"**
   A board's first successful check is a baseline. Existing jobs are stored, so we can detect when they close, but never alerted. When the source gives a trustworthy publish time, jobs from the last 48 hours are still tagged for the digest. On my first real run that was 16,336 stored vs 295 tagged, which is ~20 Gemini calls instead of ~1,090.
2. **"Polling 130 boards every 3 minutes on a tenth of a CPU — how?"**
   Conditional GET: I store each board's ETag and send it back, and unchanged boards answer `304` with no body, so 129 of 130 cost almost nothing. When a board has changed, I stream-parse it one job at a time, so a 14 MB response doesn't need 14 MB × N of heap. Requests run on virtual threads, limited to 4 per host. Database writes are 3–4 set-based statements per board, not one per job.
3. **"What happens if a job board's API glitches and returns an empty list?"**
   If a board that had 5+ jobs suddenly returns 0, I ignore it and count a failure. Only the third empty answer in a row is believed. Even then, a job needs two consecutive misses to close, and a job that reappears is reopened without a duplicate alert. So one bad response can't wipe out a company's jobs or cancel users' alerts.

---

## Block 3 — Telegram bot, onboarding and resume parsing (2026-11-24)

### What was built
- **Bot API client:** a thin client for the methods we use, with no framework.
- **Receiving updates:**
  - a webhook for production (constant-time secret check, 200 at once)
  - long polling for local development
  - a dispatcher that handles each user's updates in order and drops duplicates
- **Onboarding:** a state machine stored in Postgres. Then `/prefs`, `/skills`, `/upload`, `/pause`, `/unpause`, `/stats`, `/delete`, `/help`, `/cancel`, plus `/admin` and `/admin_tick` for the admin.
- **Resume pipeline:** PDFBox → contact details removed → Gemini (structured JSON) → normalized skills on the profile.
- **Supporting pieces:**
  - the skill vocabulary (moved forward from Block 4)
  - a Gemini REST client with retries
  - a daily Gemini budget counted per Pacific-time day (V3 migration)
- **Verified:**
  - CI: 210 unit and 37 integration tests. The bot tests drive the real webhook with fake Telegram and Gemini.
  - A live run: onboarding plus a real resume on the dev bot (32 skills extracted, confirmed by the user), while the poller found 9 new jobs in the first hour.

### Why this design
- **A thin client, not a bot framework.** We need ten methods. A framework would add its own threading, its own update loop and its own JSON stack, all of which I'd have to explain and work around. Here every request is visible and the whole client is one file.
- **Updates are handled per user, in order.** Updates go to 16 lanes by user id, and each lane runs one update at a time. Two quick taps on a toggle can't race on the same session row, and different users still run in parallel. Each lane's queue is bounded: when it's full the webhook answers 503, so Telegram redelivers later instead of us queueing without limit.
- **Idempotency.** Telegram retries webhook calls it isn't sure about. `telegram_updates` (update id as primary key) makes the second delivery a no-op; a test posts the same update twice and checks the bot answered once.
- **Secret check in constant time.** `MessageDigest.isEqual` doesn't stop at the first mismatching byte, so response timing can't be used to guess the secret one character at a time.
- **Conversation state in the database.** `bot_sessions` holds the step and the answers so far as JSON, so a redeploy in the middle of onboarding loses nothing. Old buttons (from a finished flow) are answered with "out of date" and change nothing.
- **Privacy by construction.** The resume PDF is read in memory and never stored. Contact details (emails, Indian/international phones, links, addresses and PIN codes, a name-like first line) are stripped before the text leaves the server. Only skills, years, graduation year, suggested roles and the file hash are kept, and the hash lets a re-upload skip Gemini. An integration test checks that the request Gemini receives contains none of the fake resume's contact details.
- **The model is a tool, not an authority.** Output is schema-constrained, then validated: skills normalized through the vocabulary, years clamped, unknown roles dropped. The resume is labelled untrusted in the prompt, and a real test resume containing "ignore all previous instructions" was ignored.
- **Real calls found two real problems.**
  - Gemini returned 503 "high demand" on the first try, hence retries with backoff and jitter.
  - Without today's date in the prompt, it counted "Aug 2025 – present" as 0 years. With the date it says 1.3.
- **REST instead of Google's SDK.** The SDK pulls in Jackson 2 and its own HTTP stack next to Spring Boot 4's Jackson 3. A plain HTTP API is easy to fake in tests, and the key goes in a header, never the URL.
- **The bot token is in every Telegram URL**, and Spring's exception messages include the URL. So the client rebuilds every error from Telegram's own JSON, without the original exception or its message. A test points the client at a closed port and checks the error doesn't contain the token.

### Alternatives considered
| Option | Why not |
|---|---|
| A Telegram bot framework | Hides threading and retries; more to explain than ten HTTP calls |
| One global executor for all updates | Two taps from one user could run at once and overwrite each other's session |
| Keeping onboarding state in memory | A restart or deploy mid-onboarding would lose it |
| Sending the raw resume to Gemini | Leaks names, phones and addresses to a third party for no benefit |
| An NER model for PII removal | Better recall, but it's a model to host; rules plus tests cover the realistic cases on 0.1 CPU |
| Storing the PDF "to re-parse later" | A liability; the hash is enough to skip duplicate work |

### What breaks first
- **At 10× (~50 users):**
  - **Resume parsing shares Gemini's budget with job tagging.** A burst of uploads on launch day could use up the day's calls; then the bot says "try again in a few hours", and tagging falls back to keywords. Possible fixes: a separate sub-budget for resumes, or a queue.
  - **PDFBox on 0.1 CPU:** a few seconds per resume is fine one at a time, not twenty at once.
- **At 100×:**
  - **The webhook needs more than one instance**, and then the in-memory per-user lanes no longer guarantee order. Telegram itself doesn't order updates across connections either. The fix is to route by user id (a Kafka topic keyed by user id gives exactly this).
  - The per-user command limit moves to Redis.

### Likely interview questions
1. **"You send resumes to an LLM. How do you protect users' privacy?"**
   - The file is never stored: it's read in memory and dropped, and we keep only the extracted skills, years, graduation year and a hash.
   - Before anything leaves the server, emails, phone numbers, links, address and PIN-code lines and the name line are stripped. Tests cover the tricky cases: international phone formats; "Node.js/Express" and "2021 – 2025" must survive.
   - An integration test asserts the request Gemini receives has none of the fake resume's contact details.
   - Users see the consent text before every upload, and `/delete` removes everything via cascading foreign keys.
2. **"How is your webhook secured, and what if Telegram sends the same update twice?"**
   - Telegram sends a secret header that I compare in constant time, so a wrong or missing secret gets 401.
   - I return 200 immediately and process asynchronously, because Telegram retries slow responses.
   - Every update id is inserted into a table with a primary key, so a redelivery is a no-op. A test sends the same update twice and checks the bot replied once.
3. **"A user taps two buttons quickly. How do you avoid a race on their state?"**
   Updates are routed to lanes by user id, and each lane processes one update at a time. A user's taps are applied in order against their session row, while other users run in parallel on other lanes. The lanes have bounded queues; if one is full the webhook returns 503 and Telegram redelivers later. With several instances I'd move this ordering to Kafka, keyed by user id.

## Block 4 — Tagging, matching and alerts (2026-11-24)

### What was built
- **Tagging worker:**
  - Postgres is the work queue: a posting whose description is still stored needs tagging.
  - The worker claims up to 15 rows with `FOR UPDATE SKIP LOCKED` and a 5-minute lease.
  - It sends them to Gemini in one call as `p1`…`p15`, validates every field, retries missing ones once at half size, and falls back to a keyword tagger.
  - New postings ring a doorbell; a 30-second sweep catches anything missed.
- **Matcher:** pure rules (filters, score, routing) plus a consumer of `eb.jobs.enriched.v1` and `eb.jobs.closed.v1`. "Recent matches" are sent when onboarding finishes or preferences change.
- **Notifier:**
  - message format with Apply, Prep and 👍/👎 buttons; feedback stored on the alert
  - daily caps (over the cap → digest)
  - 1 message per second per chat, and 25 per second overall through a token bucket shared in Redis (a Lua script)
  - retries; a 403 marks the user blocked
- **Digest** at 20:00 IST, top 10 per user.
- **Redis, kept optional:** `RedisGuard` wraps every call. It adds an environment key prefix, counts commands, and on failure falls back for 30 seconds.
- **Verified:**
  - CI: 314 unit and 46 integration tests, 84.5% line coverage. The pipeline test goes fake board → tick → tagging → matching → fake Telegram, and a race test delivers one alert 8 times at once.
  - Live on the laptop against real boards, Gemini, Supabase and Upstash:
    - 290+ real postings tagged.
    - The 20:00 digest fired on time.
    - The real API exposed the schema limit, and real postings exposed three fallback-tagger bugs.
  - A real instant alert in Telegram didn't come during Block 4's run: in ~75 minutes of ticks, every new job was senior or non-technical. It came on 2026-11-27, during Block 5's live run on Kafka: three real alerts (Databricks, Paytm ×2) arrived 18–27 s after the jobs were first seen.

### Why this design
- **The database is the tagging queue.** Postings already live in a table, so a "needs tagging" marker plus `SKIP LOCKED` claims gives at-least-once processing that survives restarts, with nothing new to run. Batching 15 rows is easy from a table. From a stream it means buffering and committing offsets by hand. Kafka (Block 5) still carries the events; the queue stays in Postgres.
- **15 postings per Gemini call.** The free tier allows 500 calls a day. One posting per call caps the whole service at 500 postings a day; batching raises that to 7,500.
  - Short ids (`p1`) instead of UUIDs save tokens, and the model can't garble them.
  - Ids not in the batch are ignored; postings left out are retried, then fall back.
- **Model output is validated like user input.** Unknown enum values become `other`/`unknown`, years are clamped, a max below the min is dropped, only two-letter country codes are kept, and skills go through the vocabulary.
- **The real API found a limit the docs don't spell out.** With `maxItems` on the nested arrays, Gemini refused the schema ("too many states for serving"). The limits moved into Java, and a test keeps them out of the schema.
- **A fallback so alerts never wait on Gemini.** During this block's live run Gemini answered 503 "high demand" for most batches: 16 of 20 batches (228 of 275 postings) went to the fallback. The keyword tagger kept the pipeline moving. Real postings exposed two of its bugs, both fixed with the real text as test cases:
  - "1.5 years" was read as 5 years, which hid a junior job from freshers.
  - "a global leader" in the description made a New Jersey job "remote worldwide".
- **Scoring is a pure function.** `MatchRules` has no I/O, so it's tested with tables of cases. The weights are integers (65/20/15): in floating point, `0.2 × 0.7` came out as 13.999… and rounding changed a score.
- **Duplicates are impossible by construction, not by care:**
  - `alerts` has a unique (user, posting) key and inserts use `on conflict do nothing`, so re-running the matcher creates nothing.
  - The notifier claims an alert with `update … set status = 'sending' where status = 'queued'`. Only one copy of a redelivered event wins.
  - The cap is reserved only after the claim. Writing the race test found the opposite order: losing copies used up the cap and could push the winner's alert back into the digest, so it was sent twice.
  - An integration test delivers one alert 8 times at once and checks Telegram got exactly one message.
- **Redis is an optimization, never a dependency.** Caps fall back to counting sent alerts in Postgres, and rates fall back to an in-memory bucket. Upstash dropped a connection during the live run; the guard switched to the fallbacks for 30 seconds and nothing else noticed.
- **The Gemini call counter stays in Postgres only** (a deliberate deviation from the spec's "Redis with a Postgres fallback"). One upsert per call, at most ~500 a day, is nothing for Postgres. It avoids a second source of truth that can disagree, and saves Redis commands.

### Alternatives considered
| Option | Why not |
|---|---|
| A Redis list or Kafka topic as the tagging queue | Another moving part; batching and retrying from a table is simpler and survives restarts |
| One Gemini call per posting | 15× the quota; 500 calls a day would cap the whole service |
| Embedding similarity for matching | An embedding call per posting and profile, and less explainable; "you're missing Kafka" is a set difference, which embeddings don't give you |
| Scoring in SQL | Harder to test and explain; after the role filter the candidate list is already small |
| Redis as the only store for caps | A Redis flush or outage would reset or lose them |
| Exactly-once delivery (Kafka transactions) | Heavy; a unique key plus an atomic claim gives users the same guarantee |

### What breaks first
- **At 10× (~50 users, ~1,300 boards):**
  - **Gemini capacity, not quota.** ~2,000 new postings a day is ~140 calls, well inside 500. But a long 503 spell means many postings get rough fallback tags, and nothing re-tags them later. Next step: re-tag `tagger = fallback` rows when Gemini recovers.
  - Ticks get longer (up to 45 s with 130 boards today). They need to overlap or be split by host.
- **At 100× (~500 users):**
  - **Upstash's 500K commands a month.** Each alert costs ~4 Redis commands (bucket script, cap INCR + EXPIRE). 500 users × 10 alerts × 30 days × 4 = 600K. Fixes: on a single instance, keep the rate bucket in memory; or count caps in Postgres only.
  - **Digest burst:** 500 messages at 25/s takes 20 s, which is fine. At 5,000 users it takes over 3 minutes, so it needs spreading out or a second sender.

### Likely interview questions
1. **"How do you make sure a user never gets the same alert twice?"**
   - Two layers. First, the database: `alerts` has a unique key on (user, posting), so even if the matcher runs twice for a posting, the second insert does nothing.
   - Second, the send itself: copies of the same event race to run `update … set status = 'sending' where status = 'queued'`, and only the one that changes a row sends.
   - The order matters. I first checked the daily cap before claiming, and my race test showed losing copies could use up the cap and push the alert back into the digest, so it went out twice. Now nothing happens before the claim.
   - The test fires 8 copies at once and asserts one Telegram message.
2. **"Why put 15 postings in one LLM call? What if the model skips one or makes one up?"**
   - Free-tier quota is per call, so batching multiplies capacity 15×.
   - Each posting gets a short id (`p1`…`p15`). Answers with ids I didn't send are ignored, and ids that come back missing are retried once in a smaller batch, then given keyword tags.
   - Every field is validated: enums, numeric ranges, country codes, skill names through a vocabulary.
   - Structured output has limits of its own: Gemini rejected my schema when it had array length limits, so those checks live in Java.
3. **"Redis goes down. What happens?"**
   - Nothing the user sees. Every Redis call goes through one guard; on the first failure it stops calling Redis for 30 seconds and the fallbacks answer.
   - The send rate is limited in memory, and daily caps are counted from the alerts table.
   - The cost is that with several instances the global rate becomes per-instance, so I keep the per-instance limit under Telegram's ceiling. It happened for real in the live run: Upstash dropped a connection mid-tagging, and the pipeline carried on.

## Block 5 — Kafka event bus, dead letters and the outbox relay (2026-11-27)

### What was built
- **`KafkaEventBus`**, a second implementation of the same `EventBus` interface, chosen by `EVENT_BUS=kafka`. No module changed apart from where events are built.
  - Producer: `acks=all`, idempotent, zstd, 20 ms linger, timeouts in seconds rather than minutes.
  - One listener container per consumer group (the matcher's group reads two topics). Each event is handled synchronously, and its offset is committed only after the handler and its database transaction finish.
- **Errors:** Spring Kafka's `DefaultErrorHandler` with a 1 s / 5 s / 30 s back-off, then a recoverer that publishes a `DeadLetter` (original topic, partition, offset, key, error, attempts, base64 payload) to `eb.dlq.v1`.
  - Malformed events skip the retries.
  - If the dead letter can't be written, the offset isn't committed.
  - A Telegram 4xx on an alert now dead-letters the event too.
- **`DeadLetterNotifier`** (group `eb-admin`) turns dead letters into admin notices. `/admin` shows the Kafka status, the dead-letter count and the Redis status.
- **`KafkaMonitor`:**
  - checks the five topics at startup (auto-creation is off) and the cluster every 30 s;
  - health is `DEGRADED` while Kafka is unreachable, and publishing fails at once;
  - the admin is told after 5 minutes;
  - consumers that couldn't even be created are started once Kafka answers.
- **Outbox relay** at the end of each tick. It republishes discovered postings and tag events that never reached the bus, and instant alerts still queued after 2 minutes. V5 added `posting_tags.event_published_at` and replaced an index that had silently covered every baseline posting.
- **`scripts/KafkaPemsToEnv.java`** checks Aiven's three certificate files and writes them into `.env` as one-line PEM text, printing only OK/FAIL.
- **Verified:**
  - CI: 364 unit and 52 integration tests. `KafkaPipelineIT` runs against `apache/kafka:4.4.0`: the full pipeline, a poison message, a failing consumer's 4 attempts, a redelivered event, the relay, and a paused broker.
  - Locally with Kafka unreachable: the app started, health was degraded and readiness up. That run also caught a migration bug (a duplicate index name) before CI did.
  - Live on Aiven's free plan: the first tick found 579 postings and published 554. Gemini tagged 150 in the 2.5 minutes the app ran. The matcher handled 144 events from Kafka, and three real alerts reached Telegram 18–27 s after the jobs were first seen.

### Why this design
- **Same interface, two buses.** Local development needs no broker (the "only JDK 25" rule), while production gets durability and consumer groups. Modules can't tell which bus they're on, which is also what lets the Helm chart split roles into deployments later.
- **At least once, made safe by idempotent consumers, not exactly once.** Kafka transactions would only cover Kafka. The real side effects are rows and Telegram messages, so each consumer de-duplicates where its effect happens:
  - the matcher uses `processed_events` in the same transaction as its inserts;
  - the notifier claims the alert row (`queued → sending`);
  - the tagger's queue is the table itself.
- **Commit after the transaction.** A crash between the database commit and the offset commit replays the event, which is harmless. The other order would lose it.
- **Outbox-lite instead of a full transactional outbox.** The "published" marker lives on the row itself (no outbox table), and the relay rides the existing 3-minute tick. The cost: an event can be up to a few minutes late after a crash, and may go out twice, which consumers absorb.
- **The spec's outbox had a gap.** A tag written while Kafka was down would never have reached the matcher, because the tagging queue (the description) is already cleared. So the tags got the same marker. Found by asking "what happens to each hop if Kafka is down", not by a failing test.
- **Fail fast while Kafka is down.** Without the monitor's flag, every publish would wait out a 10 s metadata timeout, and a tick with 30 new postings would stall for minutes. With it, rows stay unpublished and the relay catches up.
- **Kafka refuses to create clients for an unresolvable host.** A local test showed this. A powered-off Aiven service could look exactly like that, so client creation is lazy or retried, and the app never fails to start because of Kafka.
- **Group ids carry the environment outside prod.** Aiven allows one free Kafka service, so dev and prod share topics. With the same group ids, a laptop would take partitions, and the events on them, away from production.
- **No Boot Kafka auto-configuration.** With `spring-kafka` alone, the handful of Kafka beans exist only with `EVENT_BUS=kafka`. Subscriptions are registered in code because modules subscribe through the interface, not `@KafkaListener`.

### Alternatives considered
| Option | Why not |
|---|---|
| Kafka transactions / exactly-once | Only covers Kafka-to-Kafka; database rows and Telegram sends still need idempotency |
| A separate outbox table plus Debezium | Another service to run; a marker column and the tick are enough at this volume |
| Spring Boot's Kafka auto-configuration with `@KafkaListener` | Beans would exist even on the memory bus, and listeners would bypass the `EventBus` interface |
| Spring's `DeadLetterPublishingRecoverer` as is | Publishes the raw record; the spec's `DeadLetter` envelope (error, attempts, base64 payload) is easier for a person to read |
| Failing startup when topics are missing | One forgotten topic would take the bot and website down; a clear error plus `DEGRADED` keeps the rest working |
| One consumer per topic per group | More consumers, threads and heartbeats on 0.1 CPU for no benefit at this volume |

### What breaks first
- **At 10×:**
  - **Aiven's free throughput (250 KiB/s).** A catch-up like today's (579 postings × ~3 KB) is ~1.7 MB, a few seconds of budget, so it's fine.
  - **The relay's batch of 100 per kind per tick.** After a long outage the backlog drains over several ticks; fine at 10×, slow at 100×.
- **At 100×:**
  - **Two partitions per topic cap parallelism** at two consumers per group across all instances. The notifier is the first to need more, which means a paid plan.
  - **The free service powers off after 24 h without traffic**, and dev and prod sharing it stops being acceptable. A paid plan fixes both.
  - **Per-record offset commits** become noticeable; batch acknowledgment with idempotent handlers would be the change.

### Likely interview questions
1. **"Kafka delivers at least once. How do you avoid duplicate alerts?"**
   - By making every consumer idempotent where its side effect happens, not by exactly-once.
   - The matcher inserts a `processed_events` row in the same transaction as the alerts it creates, so a second copy of the event does nothing. Alerts also have a unique (user, posting) key.
   - The notifier claims the alert row before sending, so only one copy wins.
   - A CI test sends the same event twice, deletes the alert in between, and checks it wasn't recreated.
2. **"What happens if Kafka is down?"**
   - The app keeps working: postings, tags and alerts are written to Postgres first.
   - Publishing fails at once because a monitor knows Kafka is unreachable, and health says `DEGRADED` without touching readiness, so the platform doesn't restart the app.
   - After 5 minutes the admin gets a Telegram notice. When Kafka returns, the outbox relay publishes everything never marked as published.
   - I tested it by pausing the broker container in CI, and locally by pointing the app at a closed port.
3. **"Why commit offsets after the database transaction rather than before?"**
   - If I commit first and crash before the transaction, the event is lost.
   - If I commit after and crash in between, the event is redelivered, and the idempotent consumer makes that harmless.
   - So the order turns a possible loss into a possible duplicate, and duplicates are already handled.

## Block 6 — Scheduling with QStash, locks, backoff and maintenance (2026-11-27)

### What was built
- **`POST /internal/tick`, `/internal/digest`, `/internal/maintenance`.** They check QStash's signature, take the job's lock, answer `202` at once and run the job on a virtual thread. A run that's still going makes the next call answer `skipped`.
- **`QStashVerifier`:** a hand-written HS256 JWT check of the claims QStash documents. It accepts the current or the next key, and checks the issuer, the subject (the exact URL), `exp`/`nbf` with 10 s of skew, and the body hash (base64url SHA-256, padding ignored as in QStash's SDK).
- **Locks:** `RedisLock` (`SET NX PX`, plus a Lua compare-and-delete release), falling back to `PostgresAdvisoryLock` (a session lock on its own pooled connection). The in-memory lock is gone.
- **One job runner, `InternalJobs`,** shared by QStash's endpoints and the local timers. The nightly maintenance job is new.
- **Board backoff:** the n-th failure in a row waits 3 × 2^(n−1) minutes (capped at an hour), and the 20th disables the board. Ticks check only due boards.
- **Admin notices:** a disabled board, 3 failed ticks in a row, more than 20% of boards failing.
- **Verified:**
  - CI: unit tests include QStash test vectors (both keys, wrong key, another URL, changed body, expired, not yet valid, wrong issuer, `alg: none`). Integration tests cover 401s, 202 "skipped" while the tick lock is held, a signed maintenance call that deletes old rows, the Postgres lock, the Redis lock (only the owner releases), and the backoff schedule.
  - Locally, with tokens signed by the real QStash keys against the dev database: 401 for unsigned, wrong key and another URL; 202 started, then 202 skipped for an overlapping tick; maintenance deleted 13 old Telegram updates.
  - Not verified yet: a call actually sent by QStash. That needs a public URL, so it comes with Render in Block 8.

### Why this design
- **QStash instead of the app's own timers in production.** A free Render instance sleeps after 15 idle minutes, and a sleeping app runs no timers. An outside call every 3 minutes both wakes it and keeps it awake. QStash's free 1,000 messages a day cover 480 ticks + 2 more jobs + retries.
- **202 and background work.** The tick takes 15–50 s on a laptop, and longer on 0.1 CPU. Answering at once keeps QStash from timing out and retrying a tick that is actually running. The lock turns any retry into a cheap "skipped".
- **Verifying the subject matters.** Without it, any valid token (say, for `/internal/digest`) could be replayed against `/internal/tick` within its 5 minutes. A test proves a token for one URL fails on another.
- **Hand-written JWT check, not a library.** The format is fixed (HS256 only), and a general library brings options like other algorithms or `alg: none` that only widen what's accepted. ~100 lines and a test per rule.
- **Redis lock with a Postgres fallback.** The spec wants Redis, but Redis is optional everywhere in this app. Postgres advisory locks need no lease: the lock dies with the session. That's also why Block 0 chose Supabase's session pooler; the transaction pooler can't hold session locks. Lock names include the schema, because dev and prod share one database.
- **Backoff in SQL.** One `update … returning not enabled` computes the next check time and the disable flag in the same statement, so there is no read-then-write race between two checks.
- **"Due" includes 30 s early.** A board checked at :05 is due at the next :00 tick. Without the slack, a 3-minute board would really be checked every 6 minutes. A test pins this.

### Alternatives considered
| Option | Why not |
|---|---|
| `@Scheduled` timers in production | Render's free instance sleeps when idle, and sleeping apps don't run timers |
| GitHub Actions cron | Delayed by minutes under load, and no signed delivery |
| A JWT library (jjwt, nimbus) | More surface (algorithms, key types) for a fixed HS256 check |
| ShedLock | Another dependency and table for three locks; Redis or an advisory lock is a few lines |
| Doing the tick inside the HTTP request | QStash would time out and retry a tick that is still running |
| Linear or no backoff | A dead board would cost a request every 3 minutes forever; disabling with a nightly re-check costs one a day |

### What breaks first
- **At 10× (~1,300 boards):** one tick takes longer than 3 minutes on 0.1 CPU. The lock keeps ticks from overlapping, but boards would be checked less often than promised. Fixes: raise concurrency, split boards across instances, or give boards their own intervals (the `next_check_at` column is ready).
- **At 100×:**
  - QStash's free 1,000 messages a day is enough for the schedules, but not for one message per board.
  - The Postgres lock holds 1 of the 5 pool connections for the whole tick, while the tick itself uses up to 8 boards' worth of queries.

### Likely interview questions
1. **"How do you know a call to `/internal/tick` really came from QStash?"**
   - It carries a JWT signed with HS256 using our QStash signing key. I check the signature against the current and the next key, so key rotation needs no downtime.
   - Then the claims: issuer is Upstash; subject is exactly this endpoint's URL, so a token for another endpoint can't be replayed here; not expired and already valid; and the body's SHA-256 matches the token.
   - Unsigned or wrong gets 401. The comparison is constant-time.
2. **"What if two ticks overlap, for example QStash retries while one is still running?"**
   - Every job takes a named lock before it starts; the second call can't get it and answers 202 "skipped".
   - The lock is Redis `SET NX PX` with a lease and an owner token, so a crashed holder can't block forever and a late holder can't free someone else's lock.
   - If Redis is down, a Postgres advisory lock decides instead, and Postgres releases it automatically if the process dies.
3. **"A job board starts failing. What does your system do?"**
   - Each failure in a row doubles the wait before the next check, from 3 minutes up to an hour, so a broken board stops costing a request every tick.
   - After 20 failures in a row it's disabled and the admin is told on Telegram. Every night, maintenance re-checks disabled boards and re-enables any that answer with jobs again.
   - If more than 20% of boards fail, or 3 ticks in a row fail, that's probably our network or a platform outage rather than the boards, and the admin is told about that too.

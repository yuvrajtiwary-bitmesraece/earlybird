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

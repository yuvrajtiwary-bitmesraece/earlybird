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

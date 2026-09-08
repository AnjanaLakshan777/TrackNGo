# TrackNGo Backend Performance Guide

What was changed to make the backend faster, why each change is safe, and what
is left as an opt-in. Deployment target is the **AWS Free Tier** — one EC2
`t3.micro` and one RDS `db.t3.micro` — with the existing Render deployment still
working unchanged.

**System shape:** a 15-module Maven modular monolith (433 Java files, 37 JPA
entities, 55 tables), Spring Boot 3.2.4 on Java 21, MySQL, deployed as a Docker
image.

**Verified:** `mvn clean install` builds all 16 modules and the full test suite
passes — 0 failures, 0 errors. **Not verified here:** the application booting
against a live database, and the Docker image building (Docker is not installed
on this machine). Both need a run on your side before you rely on them.

---

## 1. The rule every change followed

Behaviour stays identical; only cost changes. Concretely:

- Every new setting is read from an environment variable with a default that
  matches or safely improves on today's behaviour, so anything can be dialled
  back on a running instance without a rebuild.
- Every cache has an explicit eviction on the write paths that could invalidate
  it. TTLs are a backstop for changes made directly in the database, never the
  primary correctness mechanism.
- Query rewrites keep the original matching rules exactly — including iteration
  order, case-insensitivity and null handling — and change only how the rows are
  fetched.

---

## 2. What changed

### 2.1 Two N+1 query patterns on the route paths

`GET /api/tracking/route-geometry` served the passenger map by loading **every**
`Route` row and then touching each one's lazy `stops` collection — one extra
`SELECT` per route. With twenty routes that is twenty-one queries to answer one
request, each a full network round trip. `GET /api/routes` (the admin listing)
had the same shape through `RouteServiceImpl.getAll()`.

Both now go through one fetch-join query, `RouteRepository.findAllWithStops()`.

The matching logic moved into a new
[RouteGeometryService.java](backend/trackngo-backend/tracking-module/src/main/java/com/trackngo/tracking/internal/service/RouteGeometryService.java)
and is carried over unchanged: normalise (trim, lowercase, strip non-alphanumeric),
prefer a route whose own endpoints match, then fall back to any route that visits
both places in the right order.

Three details worth knowing:

- **Route order is now explicit.** Both lookups take the *first* matching route,
  so the order routes are considered in is part of the answer. `findAll()` left
  it to the database; the snapshot sorts by route id, which is what an InnoDB
  full scan was producing anyway.
- **Stop order is now explicit.** The ordered-stops rule reads a stop's *position*
  in the list, so the list is sorted by priority rather than trusting whatever
  order a fetch join returns.
- **The cache holds plain records, not entities.** `Route` is a Lombok `@Data`
  class with setters on every field; caching detached instances of it would put a
  shared mutable object in front of every concurrent request. Snapshots are
  immutable records, and a fresh DTO is built per response.

### 2.2 A database read on every authenticated request

`JwtFilter` called `userDetailsService.loadUserByUsername()` for every request
carrying a Bearer token — a full round trip before the controller ran.

It now goes through
[JwtUserDetailsCache.java](backend/trackngo-backend/auth-user-module/src/main/java/com/trackngo/auth/internal/security/JwtUserDetailsCache.java).

Deliberately **not** placed on `CustomUserDetailsService` itself. That same bean
is what Spring Security's `DaoAuthenticationProvider` calls during *login*, and
the object it returns carries the password hash — caching there would let a user
sign in with an old password after changing it. The login path still reads
straight from the database. This cache is consulted only when validating an
already-issued token, where the password is never used.

What a stale entry could get wrong is a user's *role*, or a deleted user still
authenticating. Both are administrator actions, and all three of them evict the
cache immediately — `update`, `updateStatus` and `delete` in `UserServiceImpl`
carry `@CacheEvict`. `update` matters most: it changes the email, which is the
cache key.

Set `CACHE_USER_DETAILS_TTL_SECONDS=0` to switch it off.

### 2.3 A full table scan to find one booking

`TripBookingService.createBooking()` read the entire `trip_booking` table twice
via `findAll()` and filtered in Java — once to find the passenger's newest open
draft, once to find their older ones to cancel. That reads every booking ever
made to find a handful, and gets slower every day the system runs.

Both now use `TripBookingRepository.findOpenDraftsByPassenger()`, which the
existing `idx_passenger (passenger_id, booking_status)` index answers without
touching anyone else's rows. `LOWER()` keeps the comparison case-insensitive
exactly as `equalsIgnoreCase` was, rather than depending on column collation.

The second read is still a second query rather than a reuse of the first, so a
draft created between the two is still cleaned up — same as before.

### 2.4 A database write per GPS fix, on the request thread

`BusLocationRecorder` wrote to MySQL on every accepted fix. Driver phones report
every couple of seconds, so a fleet of fifty meant roughly 25 writes a second,
over the network, on the thread the driver's app was waiting on.

Two changes:

- The write is `@Async`, so the driver's POST returns as soon as the fix is
  accepted and broadcast. The passenger's map is fed from memory and never
  waited on this.
- Writes for the same bus are spaced at least
  `TRACKING_LOCATION_WRITE_INTERVAL_MS` (default 15s) apart, claimed atomically
  so two concurrent fixes cannot both pass. The first fix for a bus is always
  written immediately, so a bus that just started reporting appears at once.

The only reader of the stored row is the AI assistant answering a question
someone just typed; it does not need two-second freshness, and the live map —
which does — is not served from here. Set the interval to `0` to write every fix.

### 2.5 A three-table join every second, forever

`RefundProcessor` polled `refund × payment × seat_booking` once a second —
roughly 86,000 queries a day, essentially all returning nothing.

It now backs off: after five consecutive empty polls it queries only every
fifteenth tick, and resets to every tick the moment a refund appears. Fast
response when refunds are flowing, near-zero cost when they are not. All three
numbers are configurable.

### 2.6 Five schedulers sharing one thread

Spring's default scheduler pool is a single thread, so a slow nightly corporate
billing run held up refund processing and booking completion behind it. Now four
threads (`SCHEDULER_POOL_SIZE`). The five jobs already ran at different times
(2am, 8am, hourly, hourly, and the refund poller), so this changes when they
*can* overlap, not what any of them does.

### 2.7 Configuration

All in [application.yml](backend/trackngo-backend/app/src/main/resources/application.yml):

| Setting | Why |
|---|---|
| **Response compression** | JSON compresses 70–80%. The clients are mobile apps on Sri Lankan mobile networks — the cheapest latency win available. |
| **HikariCP pool** | Was on defaults: 10 connections, 30-minute max lifetime, no keepalive. Against a managed MySQL that produces the "first request after a quiet period is slow" stall — the pool hands out a connection the server already closed. `max-lifetime` is now 570s, under the common 600s `wait_timeout`, with keepalive probing idle connections. |
| **Tomcat threads 200 → 60** | 200 threads reserve stack memory a 1 GB instance does not have, for concurrency a 15-connection pool cannot feed. |
| **Hibernate batching** | Multi-row writes as one round trip. Needs `rewriteBatchedStatements=true` in `DB_URL` — inert without it, never incorrect. |
| **`in_clause_parameter_padding`** | Pads `IN (...)` lists to powers of two so queries differing only in list length share a prepared statement. |
| **`format_sql` true → false** | Only affects log layout; stops SQL debugging also paying for pretty-printing. |
| **Async pool** | Backs `@Async` work. Queue left unbounded on purpose — a burst of notifications should be delayed, never dropped. |
| **Actuator** | Added and secured: only `/actuator/health` is public, everything else needs authentication. |

### 2.8 Build and image

[Dockerfile](backend/trackngo-backend/Dockerfile), rewritten:

- **Dependency layer.** `COPY . .` before `mvn package` meant editing one Java
  file invalidated the layer and Maven re-downloaded the entire dependency tree
  on every deploy — the bulk of build time. POMs are now copied into their own
  stage, plus a BuildKit cache mount for `~/.m2`.
- **No `clean`.** The build context is fresh every time; it only cost time.
- **`-T 1C`.** Parallel reactor build.
- **Layered jar.** The runtime stage shipped one ~60 MB jar as a single layer, so
  every deploy pushed and pulled all of it. Layers are now extracted, ordered
  least-changing first, so a code-only deploy ships one small layer. Also
  shortens startup — the JVM reads extracted class files instead of unpacking a
  nested jar.
- **Container-aware JVM flags** via `JAVA_TOOL_OPTIONS`, so they can be retuned
  from the platform's environment without a rebuild: `MaxRAMPercentage=65` (sizes
  the heap from the container limit, not host RAM), `UseSerialGC` (beats G1 on
  one vCPU — G1's concurrent threads have no spare core), `MaxMetaspaceSize=192m`,
  `ExitOnOutOfMemoryError` (a restart instead of a GC death spiral, which to a
  user looks like an API that never responds).

---

## 3. Deploying on the AWS Free Tier

Full walkthrough: **[aws/README.md](backend/trackngo-backend/aws/README.md)**.
Supporting files in [aws/](backend/trackngo-backend/aws/) — `docker-compose.yml`,
`Caddyfile`, `setup-ec2.sh`, `deploy-ecr.sh`, `trackngo.env.example`.

The three things that matter most:

1. **Same region for EC2 and RDS.** Use `ap-south-1` (Mumbai), closest to Sri
   Lanka. Cross-region costs 150–250 ms *per query*; same region is 1–3 ms. This
   is the single largest performance factor in the deployment and it is free.
2. **Do not build the image on the `t3.micro`.** A 15-module Maven reactor on 1
   vCPU and 1 GB will crawl or be killed. Build on your machine or in CI, push to
   ECR, and let EC2 only pull.
3. **Add swap.** `setup-ec2.sh` adds 2 GB. A `t3.micro` has 1 GB and no swap; a
   JVM this size will occasionally spike past what is left after the OS and
   Docker, and with no swap the OOM killer terminates the container — which looks
   like the app dying with nothing in its own logs.

Not free, and deliberately avoided: Application Load Balancer, ECS Fargate,
App Runner, NAT Gateway. TLS is handled by Caddy on the instance instead of an
ALB, which needs a domain pointing at the Elastic IP.

---

## 4. Left as opt-in

**Deferred repository bootstrap** (`JPA_REPOSITORY_BOOTSTRAP_MODE=deferred`)
builds Spring Data repository proxies on a background thread during startup.
Default is unchanged because this app runs several `@PostConstruct` initialisers
and three `ApplicationRunner`s during startup, and that combination deserves
watching boot against a real database once before being trusted. The payoff is
cold-start only, which on EC2 (no spin-down) means deploys, so the risk was not
worth taking blind.

**AppCDS** — recipe in `aws/README.md`. Typically 20–30% off startup, needs the
database reachable at image build time.

**Virtual threads** (`spring.threads.virtual.enabled=true`) suit this workload —
nearly every request is I/O-bound on MySQL, Twilio, Stripe or the AI provider.
Two real caveats: on Java 21 a virtual thread blocking inside `synchronized`
pins its carrier thread, so audit for that first; and every request gets a fresh
thread, so anything relying on `ThreadLocal` reuse behaves differently. Enable
behind a profile and measure.

**Remaining `findAll()` calls** — 16 sites, all admin listings. Harmless at
current data volumes; they want `Pageable` before those tables grow.
`Pageable` is already used in 13 places elsewhere in the codebase.

**Client polling.** `NegotiationScreen.tsx:44` polls every 5s and
`corporate/new-contract.tsx:446` every 10s, and both dashboards poll notification
counts on a timer. A STOMP broker and two WebSocket handlers already exist —
moving these onto push would cut request volume more than any server-side change
left.

**`bus_locations` upsert.** Adding `UNIQUE KEY uq_bus_number (bus_number)` would
let the update-then-insert pair collapse into one `INSERT ... ON DUPLICATE KEY
UPDATE`. Not applied because it needs a migration against existing data that may
contain duplicate rows. With the 15s throttle the double round trip now only
happens on a bus's first fix, so the gain is small.

---

## 5. Verifying

Change one thing at a time and record the number before and after — without a
baseline you cannot tell an improvement from ordinary variance.

```bash
# per-endpoint latency (authenticated)
curl -s -H "Authorization: Bearer $TOKEN" \
  "localhost:8080/actuator/metrics/http.server.requests?tag=uri:/api/tracking/route-geometry"

# anything above 0 pending means the pool is the bottleneck
curl -s -H "Authorization: Bearer $TOKEN" \
  localhost:8080/actuator/metrics/hikaricp.connections.pending

# are the caches actually being hit?
curl -s -H "Authorization: Bearer $TOKEN" \
  localhost:8080/actuator/metrics/cache.gets

# startup cost
curl -s -H "Authorization: Bearer $TOKEN" \
  localhost:8080/actuator/metrics/application.started.time
```

To count the queries one request makes, set
`logging.level.org.hibernate.SQL=DEBUG` and count the logged statements. Thirty
`SELECT`s on one page is an N+1, not a slow database — that is the check that
found §2.1.

Load test against an already-warm instance and watch **p95**, not the mean. The
mean hides exactly the connection-pool stalls this work was about:

```bash
hey -n 500 -c 20 -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8080/api/tracking/route-geometry?start=Colombo&end=Kandy"
```

---

## 6. Rolling any of it back

Everything is one environment variable, no rebuild:

| To undo | Set |
|---|---|
| Reference-data caching | `CACHE_REFERENCE_TTL_SECONDS=0` |
| Per-request user cache | `CACHE_USER_DETAILS_TTL_SECONDS=0` |
| GPS write throttling | `TRACKING_LOCATION_WRITE_INTERVAL_MS=0` |
| Refund poller backoff | `REFUND_IDLE_BACKOFF_MULTIPLIER=1` |
| Scheduler concurrency | `SCHEDULER_POOL_SIZE=1` |
| Smaller thread pool | `TOMCAT_MAX_THREADS=200` |

The query rewrites in §2.1 and §2.3 are code, not configuration — but they return
the same rows in the same order as the scans they replaced, which is what the
route and trip-booking test suites cover.

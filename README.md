# dev.arkaitz/db-base

A pooled connection with a lifecycle, and migrations that run before anything serves.

It is the third of three libraries: `web-base` serves HTTP and never opens a database,
`auth-base` turns someone into a subject and takes storage as a port, and this is what a
host plugs into those holes. `SPEC.md` says what it is and why, decision by decision,
with the measurements behind each one; this file is only how to use it.

## Depending on it

```clojure
dev.arkaitz/db-base {:mvn/version "0.1.0"}
```

On Clojars since 2026-09-26. The engine is yours and so is its driver — declare
`org.postgresql/postgresql`, `org.xerial/sqlite-jdbc` or whichever you run — and
`ring-core` too if you use the session store.

A consumer receives a connection pool and a migration runner, and nothing else: no JSON
codec, no logging backend, no opinion about a query builder. HikariCP brings `slf4j-api`,
a facade that logs nowhere until the host supplies a backend, and ragtime brings
`next.jdbc`, which this library does not call. Integrant arrives too, with its own
`weavejester/dependency` — 23 KB between them — for the optional key below; a host that
never requires `dev.arkaitz.db-base.integrant` loads neither. A test enforces that list,
and a new arrival on it is a decision recorded with its reason.

## The lifecycle: three functions

```clojure
(require '[dev.arkaitz.db-base :as db])

(def handle
  (db/start {:jdbc-url   "jdbc:postgresql://…"     ; the host's engine, and its driver
             :user       "…"
             :password   "…"                        ; required, never defaulted, "" allowed
             :pool       {:max 10 :timeout-ms 5000}
             :migrations {:dir "db/migration" :lock-wait-ms 60000}}))

(:datasource handle)         ; a javax.sql.DataSource for next.jdbc, or whatever you use
(:migrations-applied handle) ; how many ran during this boot
(db/ready? handle 2)         ; does the database answer, within 2 seconds
(db/pool-stats handle)       ; {:active 1 :idle 9 :total 10 :waiting 0}, for your metrics
(db/stop handle)             ; closes the pool, returns nil
```

A host that wires with Integrant asks for `:dev.arkaitz.db-base/database` instead, whose
value is that same configuration map and whose `init-key` and `halt-key!` are `start` and
`stop`. Requiring `dev.arkaitz.db-base.integrant` is what installs it. It is **one** key
on purpose: the pool and its migrations are one component, and two keys would let a host
wire the pool and skip the migrations — which is the schema one deploy behind, with no
symptom until a request meets a missing table.

`start` refuses the configuration before it opens anything, then loads and checks the
migrations before any pool exists, then opens the pool, borrows one connection to prove
the database is there, and applies what is pending — under a lock, so two instances
booting at once migrate once. **A database that cannot be reached, or a migration that
fails, stops the boot** instead of the first request. Any failure after the pool opened
closes it before leaving.

`:migrations` is `:none`, or a map with `:dir`, a **classpath prefix** (not a file path —
this library never reads a file it was not handed), and `:lock-wait-ms`, how long a boot
waits for another instance to finish migrating before failing and naming it.

Every failure is an `ex-info` whose data carries `:config-key` as a vector path, plus
`:migration-id` when one migration is to blame. The JDBC URL and the password are never
echoed, in the message or in the data.

## The session store

`dev.arkaitz.db-base.session` implements Ring's `SessionStore` over a table of this
library's own, so a session can be **ended from the server** — which a signed cookie
cannot do, because the sealed value a browser holds stays valid however many empty ones
you send it afterwards.

```clojure
(require '[dev.arkaitz.db-base.session :as session])

;; ask for the table in the configuration `start` takes
:sessions {:lock-wait-ms 5000}

(def store (session/store handle {:lifetime-ms 3600000 :readers {}}))
;; a session longer than the table holds (4000 characters of EDN) is refused, the same on every engine
(session/reclaim-expired! handle)   ; the operator's sweep, and the only one there is
```

**It is the only namespace here that needs a dependency this library does not declare.**
`ring-core` is not in `:deps`: a host that wants the store brings it — every host of
web-base or auth-base already has — and a host that only wanted a pool loads neither it
nor the 1.09 MB it drags. Requiring this namespace without ring on the classpath fails as
a missing class rather than as a message of ours, and that is the price of the
arrangement.

Its migration runs **before** the host's, under a lock row and a control table of its own,
so a host's own `reset` cannot take this library's history with it.

On PostgreSQL you may name its dialect, `:sessions {:lock-wait-ms 5000 :dialect :postgresql}`,
for an unbounded `TEXT` column instead of the portable `VARCHAR(4000)`. Choose it before
the first boot: each dialect's table is recorded under its own migration id, and naming
the other one over an existing table is refused (`SPEC.md` §8 says how to move).

**What it never does is upsert.** A `write-session` under a key whose row is gone changes
nothing and says so: the row went because someone logged out, revoked it, or it expired,
and putting it back undoes a revocation. Ring's own `MemoryStore` upserts, and the one
JDBC store the ecosystem has copied that — `SPEC.md` §8 has the whole argument, and it is
the reason this library is more than wiring.

**Before you wire it, know what it costs.** A session in a row means every request touches
the database: a visitor who never signs in still leaves a row, because the CSRF token
lives in the session, and nothing removes those but `reclaim-expired!`.

## A write the engine refused

`dev.arkaitz.db-base.collision/arbitrate!` is for the write that may lose a race — an
account created twice, a row per session, a lock — where the engine refusing it is the
ordinary case and not a failure.

```clojure
(require '[dev.arkaitz.db-base.collision :as collision])

(collision/arbitrate!
 #(insert-account! ds subject identifier)   ; the write, which returns its own answer
 #(subject-for ds identifier))              ; what it was for, if it is there now
```

If the write throws an `SQLException`, the second function runs once: a truthy answer is
returned in the write's place, and nil or false rethrows the write's own exception,
unwrapped. **It never reads the SQLSTATE**, because the same refusal is 23505 on
PostgreSQL, 23000 on MySQL and nothing at all on SQLite. Two rules are yours to keep:

- **The second function asks for everything that makes the row yours**, not only its
  key. A look by key alone accepts somebody else's row as your own.
- **It reads.** A fallback that writes is update-or-insert, which is exactly what the
  session store refuses to be; where it is right for a table of yours, write it out.

It holds no SQL and takes no datasource, on purpose: a function here that accepted a
statement would be the first step of a query builder, which `SPEC.md` §9 forbids.

It is for a write in autocommit. Inside a transaction you opened, use a conditional write
instead — see the recipes below.

## In a host's tests

`dev.arkaitz.db-base.testing` is for your test suite, never a request path.

```clojure
(require '[dev.arkaitz.db-base.testing :as dbt])

(dbt/rows config "SELECT id FROM task WHERE owner = ?" owner)  ; [[1] [2]]
(dbt/one  config "SELECT COUNT(*) FROM task")                  ; 2

(let [{:keys [handle arrived release! exit]}
      (dbt/parking db-handle #(re-find #"(?i)^\s*delete" %) 10000)]
  ;; hand `handle` to the caller that must stop before its DELETE, wait on `arrived`,
  ;; run the other caller to completion, then (release!) and assert @exit = :released
  )
```

- **`rows` and `one`** read through a connection of their own, opened from the same map
  `start` takes — never the pool, because a test that reads a write back through the code
  that made it is asking the same code twice. A `Clob` comes back as text, since engines
  disagree about large-object columns.
- **`parking`** suspends the first statement your predicate accepts, so an interleaving
  is chosen rather than raced for; SQLite serialises writers, and a barrier alone passes
  a broken implementation every time. `:exit` says whether the test ended the park or
  the guard did — assert it. Stop the handle you passed in, not `:handle`; a pool of one
  deadlocks.

It makes no temporary database: that is a file, and this library reads none.

## Recipes a host keeps relearning

- **SQLite wants WAL and a busy timeout, in the JDBC URL — and, once a transaction reads
  before it writes, `transaction_mode=IMMEDIATE` too**:
  `jdbc:sqlite:app.db?journal_mode=WAL&busy_timeout=5000&transaction_mode=IMMEDIATE`. In the
  default journal a reader blocks a writer, and without a timeout the first contention is
  an error. The third one is the one nobody expects: in SQLite's default *deferred* mode a
  transaction's first read pins a snapshot, and if another connection commits before the
  transaction's first write, that write fails with `SQLITE_BUSY_SNAPSHOT` — which no busy
  timeout waits out, because it is not a wait. Measured on a host whose `join!` reads an
  event and then books a place: the loser of the race got that error, an intermittent 500.
  `IMMEDIATE` takes the write lock when the transaction begins, so the other writer waits
  instead. The URL is yours, which is why this library has no knob for it.
- **A write that may collide inside a transaction is written conditionally**, not
  arbitrated: `INSERT INTO membership (…) SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM
  membership WHERE …)`. On PostgreSQL a refused statement aborts the whole transaction, so
  `arbitrate!`'s look afterwards has nothing to look through — and SQLite, which keeps the
  transaction alive, would let the mistake pass every test.
- **Counters and epoch milliseconds are `BIGINT`, never `NUMERIC`.** A `NUMERIC` column
  comes back as a `BigDecimal`, and `(= 0M 0)` is false in Clojure — so a revocation
  generation compared with `=` silently never matches.
- **If your schema must run on more than one engine, write it the way §8's table is
  written**: `VARCHAR` with a bound you enforce yourself, `BIGINT`, and no clause one
  family takes and another refuses. Long text has no type every engine takes — PostgreSQL
  refuses `CLOB`, HSQLDB and Derby refuse `TEXT` (measured 2026-09-26) — so either bound it
  or give each engine its own migration, as this library's dialects do. The suite's own dialect table, in
  `test/dev/arkaitz/db_base/dialect_test.clj`, says which engine refuses which form.

## Running it in production

Three things to wire, and all three are yours to schedule, because a library that started
threads would be a lifecycle you did not ask for (`SPEC.md` §9).

- **Health.** `(db/ready? handle 2)` borrows a connection and asks the driver whether it
  is valid, within the seconds you give it — as far as the driver honours them; pgjdbc
  does, H2 over TCP does not (`SPEC.md` §6).
- **Load.** `(db/pool-stats handle)` answers `{:active :idle :total :waiting}` from
  HikariCP's counters. `:waiting` above zero for long means the pool is exhausted, not
  that the database is slow; `:total` climbs to `[:pool :max]` on its own after `start`.
  It refuses a stopped pool rather than reporting its zeros.
- **Reclaiming sessions.** Expired session rows are already invisible — the expiry is in
  every read — so reclaiming is about disk, and nothing does it unless you call
  `session/reclaim-expired!`. Call it from a scheduler your host owns, and stop that
  scheduler before `db/stop`:

  ```clojure
  (import '[java.util.concurrent Executors TimeUnit])

  (def sweeper (Executors/newSingleThreadScheduledExecutor))
  (.scheduleWithFixedDelay sweeper
                           #(try (println "reclaimed" (session/reclaim-expired! handle))
                                 (catch Exception e (println "reclaim failed:" (ex-message e))))
                           0 1 TimeUnit/HOURS)
  ;; and on the way down, before db/stop:
  (.shutdown sweeper)
  (.awaitTermination sweeper 10 TimeUnit/SECONDS)
  ```

  Hourly is plenty for a table that only ever needs to stop growing; a cron job running a
  one-shot command does the same. The count it returns is how many sessions expired
  unread since the last sweep, which on a public site is mostly visitors who never signed
  in.

## What it will not do

- **Read a file nobody named.** Configuration arrives as a map. The connection details,
  password included, live in a `*.local.edn` the host reads and that never reaches a
  repository.
- **Default a password**, or generate one. A value invented at startup works beautifully
  in development and differs per instance in production.
- **Decide your transaction boundary**, or hand out a connection per request. That is the
  host's, and deciding it here would make every consumer's request handling this
  library's business.
- **Run a background thread** — no sweeper on a timer, no lifecycle the host did not ask
  for.
- **Assume an engine.** Any JDBC database; the host brings the driver. The suite proves
  it on two engines from different dialect families, and a scan refuses to find a
  one-vendor dialect spelled anywhere in `src`.

## Running the tests

```
clojure -M:test                        # the whole suite
clojure -M:test -n <namespace>         # one namespace
clojure -M:test -v <namespace>/<var>   # one test
```

They run on H2 and SQLite, embedded, with no infrastructure — deliberately not the
engine this is deployed on, because a suite that ran on that engine would accept every
mistake shaped like it.

## The hosts that consume it

There are two, and they prove different things.

### `demo-tasks/` — the three libraries together

A small CRUD application on all of them at once: [web-base](https://clojars.org/dev.arkaitz/web-base)
serves it, [auth-base](https://clojars.org/dev.arkaitz/auth-base) decides who is asking,
and this library opens the database and applies the schema before anything answers a
request. Sign in with a magic link printed to the console — any address works, and the
account comes into being the first time a link is redeemed — then keep a list of tasks
nobody else can see.

```
clojure -M:demo-tasks         # serves on the port config.edn names
clojure -M:demo-tasks 3141    # or on the one you name; the sign-in link follows it
clojure -M:demo-tasks-test    # the acceptance test of the three-way join
```

**It is the host that makes the case for §8's session store**, because it has the one
feature a sealed cookie cannot serve: a page listing where you are signed in, and a
button that ends **one** of those sessions while the others keep working. Its test suite
carries the control — the same host, the same handler, the same routes over a signed
cookie — which asserts that everything else still works and that this one call cannot
end anything.

It writes the adapter this library is not allowed to contain: auth-base's `Store` port,
five methods over the datasource db-base handed it. 48 lines, which is what "a page of
code" turns out to mean.

It consumes auth-base from `../auth-base` rather than from Clojars, because the
Integrant key and the registration hook it needs were written for it and are not in
0.1.0 yet. That is a coordinate you would change; it is not a pattern to copy.

### `demo/` — this library and web-base, alone

`demo/` is a small web application that serves pages with
[web-base](https://clojars.org/dev.arkaitz/web-base) and opens its database with this
library. It exists to answer one question — *does db-base serve a real host as it
stands?* — and it is where the two libraries meet: neither mentions the other, the host
gives each a map, and Integrant's `#ig/ref` is the whole of the coupling.

```
clojure -M:demo          # serves on the port config.edn names
clojure -M:demo 3141     # or on the one you name
clojure -M:demo-test     # the acceptance test of the join
```

It keeps its notes in `demo.db`, a SQLite file the host names and the first boot
migrates; stop the process, start it again, and the notes are still there while the
second boot applies nothing. Nothing it needs is on this library's `:paths` or `:deps` —
the demo lives in aliases, so nothing a consumer receives changes because it exists.

**One thing it turned up, which is the host's and not this library's**: when an
Integrant key fails, the `ex-info` Integrant throws carries `:value`, the resolved
configuration for that key — JDBC URL and password included. This library's own refusals
echo neither, at any link of the cause chain. So what a host logs is the *cause*, never
the exception Integrant threw. The seam test pins both halves.

## The honest part

`SPEC.md` §12 argues that this library may not deserve to exist, and states the condition
under which it should be deleted: if, after a second consumer, it is still nothing but
the wiring of §6 and §7, it should be folded back into the applications. The case for it
is that the *decisions* are the value — which pool, which migration tool, what happens
when a migration fails at boot, whether a password may ever be defaulted — and those get
re-litigated in every project otherwise.

It has **two** consumers, the hosts above, and the exit condition is not met — which is
a narrower claim than it sounds. §6 and §7 remain wiring anybody could write; what the
second host showed is that §8 is a contract a real feature depends on, one a sealed
cookie cannot serve. The two namespaces added on 2026-09-25 did not change that: no
consumer's code asked for them, their tests did. Read §12 before adding anything; that
section is the point of the whole thing.

## Licence

See `LICENSE`.

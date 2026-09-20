# dev.arkaitz/db-base

A pooled connection with a lifecycle, and migrations that run before anything serves.

It is the third of three libraries: `web-base` serves HTTP and never opens a database,
`auth-base` turns someone into a subject and takes storage as a port, and this is what a
host plugs into those holes. `SPEC.md` says what it is and why, decision by decision,
with the measurements behind each one; this file is only how to use it.

## Depending on it

There is no artifact published anywhere. It is a git dependency:

```clojure
io.github.arkaitz-dev/db-base {:git/tag "v0.1.0"}
```

and let tools.deps fill in the sha, which is the part it verifies:

```
clojure -X:deps git-resolve-tags
```

A consumer receives a connection pool and a migration runner, and nothing else: no JSON
codec, no logging backend, no opinion about a query builder. HikariCP brings `slf4j-api`,
a facade that logs nowhere until the host supplies a backend, and ragtime brings
`next.jdbc`, which this library does not call. Integrant arrives too, with its own
`weavejester/dependency` — 23 KB between them — for the optional key below; a host that
never requires `dev.arkaitz.db-base.integrant` loads neither. A test enforces that list,
and a new arrival on it is a decision recorded with its reason.

## The three functions

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

## The honest part

`SPEC.md` §12 argues that this library may not deserve to exist, and states the condition
under which it should be deleted: if, after a second consumer, it is still nothing but
the wiring of §6 and §7, it should be folded back into the applications. The case for it
is that the *decisions* are the value — which pool, which migration tool, what happens
when a migration fails at boot, whether a password may ever be defaulted — and those get
re-litigated in every project otherwise.

As of this writing it has no consumer. Read §12 before adding anything.

## Licence

See `LICENSE`.

# dev.arkaitz/db-base

A pooled connection with a lifecycle, and migrations that run before anything serves.

It is the third of three libraries: `web-base` serves HTTP and never opens a database,
`auth-base` turns someone into a subject and takes storage as a port, and this is what a
host plugs into those holes. `SPEC.md` says what it is and why, decision by decision,
with the measurements behind each one; this file is only how to use it.

## Depending on it

There is no artifact published anywhere, **and no tag either** — this repository has
never had one, so the `{:git/tag "v0.1.0"}` these lines used to recommend resolved to
nothing for anybody who tried it. Corrected 2026-09-22, when the second host consumed
this library and the instructions were read as instructions rather than as prose.

Until there is a release, depend on a commit:

```clojure
io.github.arkaitz-dev/db-base {:git/url "https://github.com/arkaitz-dev/db-base"
                               :git/sha "<the commit you want>"}
```

or, working on it alongside a host of your own, from disk — which is what
`demo-tasks/` does with its sibling auth-base, and the honest coordinate while a
library and its consumer are being written together:

```clojure
dev.arkaitz/db-base {:local/root "../db-base"}
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

**What it never does is upsert.** A `write-session` under a key whose row is gone changes
nothing and says so: the row went because someone logged out, revoked it, or it expired,
and putting it back undoes a revocation. Ring's own `MemoryStore` upserts, and the one
JDBC store the ecosystem has copied that — `SPEC.md` §8 has the whole argument, and it is
the reason this library is more than wiring.

**Before you wire it, know what it costs.** A session in a row means every request touches
the database: a visitor who never signs in still leaves a row, because the CSRF token
lives in the session, and nothing removes those but `reclaim-expired!`.

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

As of this writing it has **one** consumer, the `demo/` above, and the exit condition
needs two. §8's store is written and that demo uses it — but §12 records, measured and
in full, that the host's own features would work under a cookie, so nobody has yet
*needed* the part of this library that is not wiring. Read §12 before adding anything;
that paragraph is the point of the whole thing.

## Licence

See `LICENSE`.

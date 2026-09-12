# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in
this repository.

`SPEC.md` says **what** to build and why. This file says **how to work here**, and
repeats only the few rules that get broken silently.

## What this is

`dev.arkaitz/db-base` — a pooled connection with a lifecycle, migrations that run
before anything serves, and the decisions behind both. The third of three:
`web-base` serves HTTP and never opens a database; `auth-base` turns someone into a
subject and takes storage as a port; this is what a host plugs into those holes.

## Project state

**Specification settled 2026-09-09; `start` and `stop` written 2026-09-12.** Commands go
in this file **only once they have actually been run and observed to work**, never from
convention. Observed:

    clojure -M:test                        # whole suite; prints "Ran N tests containing M assertions."
    clojure -M:test -n <namespace>         # one namespace
    clojure -M:test -v <namespace>/<var>   # one test

`clojure -M:test -e '…'` does **not** evaluate: `:test`'s `:main-opts` hand the arguments
to the test runner, which reads `-e` as `--exclude` and runs the tests as usual — the form
is silently never evaluated (observed 2026-09-12: the tests ran, the form printed nothing).
To evaluate against the test classpath, run `java -cp "$(clojure -Spath -M:test)"
clojure.main -e '…'` from the repository root, whose relative `src` and `test` entries the
classpath needs (observed 2026-09-12), remembering that this skips `:test`'s `:jvm-opts`.

**Tools, chosen by the user from measurements run 2026-09-11** (numbers in the session's
memory, not re-derived here): HikariCP 7.1.0 as the pool, with fail-fast initialisation
off because its default constructor connects with no deadline; ragtime.next-jdbc 0.12.1
for migrations, which has no lock, so db-base writes its own (not yet designed); H2
2.5.250 and SQLite 3.53.4.0 as the strict and permissive test engines. Rejected with
measured reasons: c3p0 (prints the JDBC URL through JUL), dbcp2 and Agroal (neither bounds
the caller against a silent socket), migratus (an orphan reservation row stops every later
run silently) and Flyway (a failure on a non-transactional-DDL engine needs `repair`).

## Active work

- [x] `deps.edn`: Clojure, HikariCP and ragtime.next-jdbc in `:deps`; the two test
      engines on `:test` only. `next.jdbc` is not declared, because db-base does not call
      it; ragtime brings it.
- [x] `src/dev/arkaitz/db_base.clj`: `start` and `stop` as SPEC §6 specifies, with the
      configuration refused before anything opens, the pool bounded and closed on every
      failure, and the structure scans of §3, §5 and §9.
- [ ] `ready?`, as SPEC §6 specifies.
- [x] Choose the pool and the migration library by running them (above).
- [ ] Migrations inside `start`, verifying §7's failure class — a failure at N leaves
      1..N-1 applied and recorded, N unrecorded, `start` throws naming N, next boot
      retries N — with a lock of db-base's own, whose design goes to the user first.
- [ ] The two-engine test with its positive control: the proof of §3.
- [ ] **The production engine is unchosen.** The test engines are H2 and SQLite; the
      2026-09-11 discussion leaned PostgreSQL for the host — `DELETE … RETURNING` makes
      auth-base's `take-challenge!` atomic in one statement — but §3 still requires
      agnosticism. Naming it is a §3 edit plus §8's column types, and needs an explicit
      decision.

Three threads belong to **auth-base**, not here, and need raising before that repository
is touched:

- [ ] `auth-base/SPEC.md:159` contradicts §8 of this document about who ships the JDBC
      implementation of its Store port, and still calls this project `base-db`.
- [ ] auth-base has no ceremony for attaching a second identifier to an existing
      subject — log in by email, later add a phone. Its §15 lists "the second factor",
      which is a different thing.

Done, recorded so it is not repeated: the upsert trap of §8 was reported upstream at
`luminus-framework/jdbc-ring-session` issue 25.

## The three rules that must survive contact with code

**1 · Impose what you are, not what you use.** A consumer must receive a pool and a
migration runner, and nothing else: no JSON codec, no logging backend, no opinion
about their query builder. A data layer is where transitive dependencies breed.

**2 · Nothing here may be PostgreSQL-shaped.** Any JDBC database. The first function
that assumes `jsonb`, `LISTEN` or `RETURNING` hands the library to one engine, and it
will be written by someone who only has that engine in front of them.

**3 · It may implement a port defined by a stable third party; it must never depend on
a sibling module of ours.** Ring's session store is three functions old enough to
trust. auth-base's store is ours, and an implementation here would lock two of our
libraries to each other's releases in both directions.

## Traps already identified — do not rediscover them

**Every one of these fails silently, which is why they are written down before there
is any code to break.**

- **A datasource in a var root.** Ambient global state is the mistake web-base was
  built to avoid, with a connection attached. It also bakes into a native image, as
  web-base found the hard way with its random generators.
- **A defaulted password.** A value invented at startup works beautifully in
  development and differs per instance in production, exactly as web-base's session
  key would have.
- **Migrations that fail without stopping the boot.** A schema one deploy behind the
  code reports itself as a bug somewhere else entirely, hours later.
- **Reading a file nobody named.** Configuration arrives as a map. A library that
  knows a filename can look for it, and then the directory a process started from
  decides which database it opens. The other half of the rule is where that map comes
  from, and it is not optional either: the connection details, password included, live
  in a `*.local.edn` that the host reads and that never reaches the repository.
- **A connection per request, decided here.** The transaction boundary is the host's.
  Deciding it here makes every consumer's request handling this library's business.
- **`take-challenge!` implemented as read-then-delete**, when auth-base's adapter is
  eventually written. Single use is the whole security of a link that travels by
  email, and two statements have a window between them.
- **The session store written as an upsert**, or as update-then-insert-if-zero. Ring
  never asks for one, and re-inserting a row whose update touched nothing resurrects a
  session someone revoked. Ring's own `MemoryStore` upserts, so the reference
  implementation is the wrong answer here. SPEC §8.
- **A migration run that applies zero migrations reporting success.** An empty or
  misspelt directory is the schema one deploy behind. Same shape as a test run that
  ran no tests: the count is the signal.
- **A sweeper on a timer.** A background thread is a lifecycle the host did not ask
  for and a shutdown path that gets forgotten. SPEC §9.
- **H2's wrong-password delay is JVM-global.** Each refused login doubles a static delay
  and the next correct login anywhere sleeps part of it, so one test's wrong password
  made another test's one-second borrow fail at random. `:test` sets
  `-Dh2.delayWrongPasswordMin=0`; a JVM started without it (a REPL, `java -cp`) re-arms it,
  and the wrong-password test's witness says so. Never `delayWrongPasswordMax=0`, which
  means no maximum.
- **HikariCP keeps the login timeout on `DriverManager`, which the whole JVM shares.** A
  pool's close waits for the login timeout of the last pool constructed, so timing tests
  keep their timeouts within the same whole second.
- **What HikariCP and the driver read on their own is the host's JVM.**
  `hikaricp.configurationFile` configures the pool from a file, and a driver reads its
  own files; SPEC §6 records both as the host's and db-base refuses neither.

## Where the boundary is expected to erode

**Queries.** The first helper for `where` looks harmless and is the beginning of a
query language. `next.jdbc` already accepts a datasource and a vector; anything on top
of that has to justify itself against §9 of the specification, which lists what this
library must never own.

## The honest state of this repository

SPEC §12 argues that this library may not deserve to exist, and states the condition
under which it should be deleted: if after a second consumer it is still nothing but
the wiring of §6 and §7, fold it back into the applications. That paragraph is not
decoration. Read it before adding anything.

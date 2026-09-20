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

**Specification settled 2026-09-09. `start` and `stop` written 2026-09-12, `ready?`
2026-09-13, the migration run 2026-09-19, its lock 2026-09-19, and §3's two-engine
proof 2026-09-20.** What §6 and §7 specify is written and tested; §8's session store and
§10's Integrant namespace are not, and §12 is the argument for keeping it that way until
a second consumer asks. Commands go in this file **only once they have actually been run
and observed to work**, never from convention. Observed:

    clojure -M:test                        # whole suite; prints "Ran N tests containing M assertions."
    clojure -M:test -n <namespace>         # one namespace
    clojure -M:test -v <namespace>/<var>   # one test

`clojure -M:test -e '…'` does **not** evaluate: `:test`'s `:main-opts` hand the arguments
to the test runner, which reads `-e` as `--exclude` and runs the tests as usual — the form
is silently never evaluated (observed 2026-09-12: the tests ran, the form printed nothing).
To evaluate against the test classpath, run `java -cp "$(clojure -Spath -M:test)"
clojure.main -e '…'` from the repository root, whose relative `src` and `test` entries the
classpath needs (observed 2026-09-12), remembering that this skips `:test`'s `:jvm-opts` —
so a probe that talks to H2 passes `-Dh2.delayWrongPasswordMin=0` itself. A file path in
place of `-e` runs a whole scratch probe the same way (observed 2026-09-20), which is how
the dialect matrix of §3 was measured before any test pinned it.

**Tools, chosen by the user from measurements run 2026-09-11** (numbers in the session's
memory, not re-derived here): HikariCP 7.1.0 as the pool, with fail-fast initialisation
off because its default constructor connects with no deadline; ragtime.next-jdbc 0.12.1
for migrations, which has no lock, so db-base writes its own (designed from measurements
2026-09-14, SPEC §7); H2 2.5.250 and SQLite 3.53.4.0 as the strict and permissive test
engines. Rejected with
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
- [x] `ready?`, as SPEC §6 specifies: the handle and the timeout refused before it
      borrows, everything from the borrow on answered as true or false with an `Error`
      excepted, and its time bound left to the driver (decided with the user 2026-09-13,
      after measuring both).
- [x] Choose the pool and the migration library by running them (above).
- [x] Migrations inside `start`, verifying §7's failure class — a failure at N leaves
      1..N-1 applied and recorded, N unrecorded, `start` throws naming N, next boot
      retries N — under the lock SPEC §7 records (decided with the user 2026-09-14).
      Done 2026-09-19: the source is checked before any pool exists, the run sits under
      the C+ lock, and two boots provably overlap in the test.
- [x] The two-engine test with its positive control: the proof of §3. Done 2026-09-20:
      the whole dialect matrix is fired at H2 and SQLite and pinned engine by engine
      (`dialect_test`), and no string literal in src may spell one of those forms
      (`structure_test`). SPEC §11 records the pair as settled.
- [ ] **The production engine is unchosen.** The test engines are H2 and SQLite; the
      2026-09-11 discussion leaned PostgreSQL for the host — `DELETE … RETURNING` makes
      auth-base's `take-challenge!` atomic in one statement — but §3 still requires
      agnosticism. Naming it is a §3 edit plus §8's column types, and needs an explicit
      decision. **MySQL and MariaDB were measured 2026-09-20** (MariaDB 13.0.2, MySQL
      26.7.0, with their own drivers), because they were the obvious alternatives:
      - db-base runs on both **unmodified**. A real `start` applied the three fixtures,
        a second boot applied none, a source missing one was refused naming it, one more
        applied, the lock row was given back. ragtime's `varchar(255)` key is 1020 bytes
        and fits InnoDB's 3072 under the `dynamic` row format both default to — a server
        configured to the old 767 would not take the control table at all, which is the
        setting to check before choosing either.
      - The difference that decides: **MariaDB has `INSERT`/`DELETE … RETURNING` and
        MySQL 26.7 still does not.** On MySQL the single-use take becomes a conditional
        `UPDATE` that claims the row and then a read — still atomic, but a different
        design, and it belongs to auth-base rather than here.
      - Both refuse `ON CONFLICT`, `LISTEN`, `NOTIFY`, `MERGE`, `jsonb` and a value wider
        than its column; both take `CREATE TABLE IF NOT EXISTS` and
        `ON DUPLICATE KEY UPDATE`. Neither belongs in the test pair: they are not
        embedded, and they refuse nothing H2 does not refuse already.

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
- **A case-insensitive collation under a case-sensitive id.** MySQL and MariaDB default
  to `utf8mb4_0900_ai_ci` and `utf8mb4_general_ci`, so two migration ids that differ only
  in case are one id to the control table's primary key, while H2 and SQLite take both
  (measured 2026-09-20). §7 refuses duplicate ids case-sensitively, in Clojure, so the
  pair `001-a` and `001-A` passes this library and then collides in the engine. Loud
  where it happens, invisible to the suite. The same family differs from itself on table
  names: `lower_case_table_names` was 2 on macOS against 0 on the Linux image.
- **A docstring in `src` that spells what the scan forbids.** §3's scan reads every
  string literal, docstrings included, and §5's reads them for configuration file names.
  Explaining *why* the library avoids `ON CONFLICT`, an existence clause on `CREATE
  TABLE` or a `.edn` name therefore reds the suite — measured the day the scan was
  written, on `lock-table-ready!`'s own docstring. Say it without spelling it, and say
  in the docstring that this is why; never soften the scan to let the prose through.
- **A lock row nobody is waiting for.** A boot with nothing pending never asks for the
  lock, which is what keeps an ordinary restart from leaving a row behind — and also what
  makes a row left by a failed release invisible: every restart with nothing to apply
  starts clean and says nothing, until the first deploy that does have a migration fails
  naming a holder from days ago. The failure is loud when it matters and silent until
  then, on purpose; a test pins both halves.
- **HikariCP keeps the login timeout on `DriverManager`, which the whole JVM shares.** A
  pool's close waits for the login timeout of the last pool constructed, so timing tests
  keep their timeouts within the same whole second.
- **What HikariCP and the driver read on their own is the host's JVM.**
  `hikaricp.configurationFile` configures the pool from a file, and a driver reads its
  own files; SPEC §6 records both as the host's and db-base refuses neither.
- **H2 over TCP ignores `isValid`'s timeout and `setNetworkTimeout`.** Against a
  server that stops answering, `ready?` and any borrow of a connection idle for more than
  500 ms block until a `NETWORK_TIMEOUT` in the URL expires — still blocked after 12 s
  without one (measured 2026-09-13). A test that needs H2 to fail fast stops the server,
  which closes its sockets, rather than silencing it.

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

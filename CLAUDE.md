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
proof 2026-09-20, and §10's Integrant key the same day.** What §6, §7 and §10 specify is
written and tested; §8's session store is not. §12's gate is *a consumer that asked* —
one, not two; two is its **exit** condition, which is a different sentence — and the key
entered on that basis: the host now being built in this repository serves HTTP through
web-base, which is wired with Integrant, so the library ships the key rather than making
that host write it. §8 stays out until the same gate opens for it. Commands go in this file **only once they have actually been run
and observed to work**, never from convention. Observed:

    clojure -M:test                        # whole suite; prints "Ran N tests containing M assertions."
    clojure -M:test -n <namespace>         # one namespace
    clojure -M:test -v <namespace>/<var>   # one test
    clojure -M:demo [port]                 # the host of §12, serving; the port is optional
    clojure -M:demo-test                   # the acceptance test of the seam

The demo is on `:demo` and `:demo-test` and **must never reach `:test`**:
`structure_test`'s logging-backend scan reads the running JVM's classpath, and the web
stack brings logback. Its own docstring says a backend arriving through a test extra
"reds falsely but visibly, and the fix is to move it, never to filter the scan".

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
- [x] **The production engine is PostgreSQL** (decided with the user 2026-09-20, written
      into SPEC §3 and §11). The test engines stay H2 and SQLite — deliberately *not* the
      production engine, because a suite that ran on it would accept every
      PostgreSQL-shaped mistake in silence. §8's types were already measured across five
      engines and do not change. What the decision unblocks is auth-base's
      single-statement `take-challenge!`, and that belongs to that repository.

      **All three candidates were run against this library, unmodified, on 2026-09-20**,
      each with its own driver, the same fixtures and the same four boots — apply three,
      apply none, refuse a history the source has lost, apply one more. PostgreSQL 18.6,
      MariaDB 13.0.2 and MySQL 26.7.0 **all passed**, so the decision was not about
      whether they work:
      - **`RETURNING` decided it.** PostgreSQL and MariaDB have it; MySQL 26.7 still does
        not, which turns the single-use take into a conditional `UPDATE` and a read.
      - **pgjdbc honours `isValid`'s timeout** against a socket that stays open and says
        nothing, where H2 over TCP does not — see the trap below. No dialect table shows
        that, and it is what `ready?` is for.
      - **The duplicate key has three spellings**: `23505` on PostgreSQL, `23000` with
        vendor 1062 on MySQL and MariaDB. That is why §7 reads the row instead of
        classifying by SQLSTATE.
      - **A trap for the MySQL family, if it is ever revisited**: their default collation
        is case-insensitive, so `001-a` and `001-A` are one id to the control table's
        primary key while H2 and SQLite take both — §7 refuses duplicates
        case-sensitively, in Clojure, so this library passes the pair and the engine
        collides on it. Also ragtime's `varchar(255)` key is 1020 bytes: it fits InnoDB's
        3072 under the `dynamic` row format both default to, and not the old 767.
      - **The suite's `MERGE` cell measures a spelling**, not the statement: PostgreSQL 18
        takes `MERGE` with its own `SET v = …` and refuses the qualified `SET t.v` that H2
        takes and `dialect_test` fires. SQLite has no `MERGE` at all, so §3's claim holds.

- [x] **A consumer, so §12 is a question with an answer** (2026-09-20/21). `demo/` is a
      web application that serves pages with web-base 0.2.0 from Clojars and opens its
      database with this library, wired by Integrant, with an acceptance test of the
      join in `demo/test/demo/seam_test.clj`. **Nothing was missing**: the host needed no
      library code that did not already exist, and the only thing added was §10's
      Integrant key, which §10 already specified. That fits and also fails to move §12's
      argument — written up there, because that paragraph is the falsification test.
- [x] **§8's session store, written 2026-09-21** — `dev.arkaitz.db-base.session`, its own
      migration run before the host's, `ring-core` undeclared so a host that only wanted a
      pool loads neither it nor ring. The demo's session is a row, and an acceptance test
      proves what a cookie cannot do, with a control that boots the SAME host over the
      cookie store and watches the copy still work there.
- [ ] **§12's gate was satisfied in form and not in substance, and that is recorded
      rather than fixed** (decided with the user 2026-09-21, written into §12). The demo's
      own features — naming yourself, ending your session — work under a cookie for an
      honest client; what needs a row is a thief replaying a copy, which no feature of the
      host exposes. The store was committed first and the consumer fitted to it
      afterwards. The feature that WOULD ask — list my sessions, end another by id —
      cannot be built without the host reading `db_base_sessions`, which §8 says is this
      library's and not the host's: either db-base grows a listing surface, which §9 is a
      list of reasons against, or the host reaches in. Open on purpose, for the second
      consumer to decide.

- [ ] **§7's lock release is scoped by holder, and nothing pins that scoping.** Found by
      the §8 panel on 2026-09-21, unanimous across four lenses. `release-lock!` deletes
      `WHERE id = ? AND holder = ?`; drop either predicate and both suites stay green,
      because no test ever constructs the case the `holder` clause guards — boot A dies
      holding a row, boot B later takes that same id with a fresh holder, and A's stray
      release-after-failure deletes B's live row. `a-boot-gives-back-its-own-lock-row-and-no-other`
      plants two rows but both with foreign holders, so it misses it too. Severity: a
      cross-instance defect that only shows under a failure path, which is where it would
      hurt most. Deferred because building it needs a boot that fails *while holding*, and
      that is §7's suite rather than §8's.

Two belong to **web-base**, found by building the demo against it and measured
2026-09-21. Neither is db-base's to fix and both are worth raising there:

- [ ] **Its error handler sits inside its session middleware.** `web_base.clj` wraps
      `ring-handler` with `error/default-handler` innermost and applies `session/wrap`
      later, so with a server-side store a database that is down makes a request die at
      the adapter instead of reaching the error page. The measured consequence here: the
      demo's `/health` computes a 503 that never leaves, and `ready?` became unobservable
      through the stack — a route that never calls it survives the whole suite.
- [ ] **Every request without a cookie writes a session row**, because ring-anti-forgery
      keeps its token in the session. Three health probes, three rows; a balancer polling
      every five seconds writes seventeen thousand a day, and §8's rows are reclaimed only
      by a function the operator calls. A route that wants no session has no way to say so.

Three threads belong to **auth-base**, not here, and need raising before that repository
is touched:

- [ ] `auth-base/SPEC.md:159` contradicts §8 of this document about who ships the JDBC
      implementation of its Store port, and still calls this project `base-db`.
- [ ] auth-base has no ceremony for attaching a second identifier to an existing
      subject — log in by email, later add a phone. Its §15 lists "the second factor",
      which is a different thing.
- [ ] With the engine decided (2026-09-20), auth-base's `take-challenge!` can be the one
      `DELETE … RETURNING` that makes a single-use link atomic, which is the trap listed
      below as read-then-delete. The adapter belongs there and not here (rule 3), so the
      action from this side is to raise it, not to write it.

Done, recorded so it is not repeated: the upsert trap of §8 was reported upstream at
`luminus-framework/jdbc-ring-session` issue 25.

## The three rules that must survive contact with code

**1 · Impose what you are, not what you use.** A consumer must receive a pool and a
migration runner, and nothing else: no JSON codec, no logging backend, no opinion
about their query builder. A data layer is where transitive dependencies breed.

**2 · Nothing here may be PostgreSQL-shaped.** Any JDBC database. The first function
that assumes `jsonb`, `LISTEN` or `RETURNING` hands the library to one engine, and it
will be written by someone who only has that engine in front of them. **Since 2026-09-20
that someone is us**: the host's engine is PostgreSQL, so the engine that takes every
forbidden form is now the one in every terminal. That is why the suite runs on H2 and
SQLite instead, and why `dialect_test` and the literal scan exist — they are the only
things standing between a convenient `RETURNING` and a library that belongs to one
vendor.

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
- **A value minted at load time, kept in a var root.** `(def holder (random-uuid))` looks
  harmless and is fine on a JVM. Under a native image built the way Clojure images are
  built — `--initialize-at-build-time` — that value is computed while the class
  initialises and **baked into the binary**: the same in every run of every instance, with
  no error and no warning (measured 2026-09-20 on GraalVM CE 25.3.4.1, on a real
  AOT-compiled Clojure namespace). §9's own scan does not catch it — it hunts state and
  this is a constant — so `structure_test` grew one beside it: no var root of src may reach
  a UUID, a generator, or a string carrying a UUID's spelling. The lock holder of §7 is
  minted inside a function, per boot, and that scan is what keeps it there; web-base
  learned the same lesson through its random generators. What no scan sees is a baked value
  shaped like any other — a clock reading, a pid — and asking the image builder to
  initialise this namespace at run time is not a fix: it builds, and the binary then dies
  on startup.
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
- **Integrant's own failure carries the configuration.** When an `init-key` throws,
  `ig/init` does not halt what it built: it throws an `ex-info` whose data holds
  `{:reason ::ig/build-threw-exception :key K :system <the partial system> :value V}`,
  and `V` is the resolved configuration for that key — **JDBC URL and password
  included** (measured 2026-09-21, pinned in `demo/test/demo/seam_test.clj`). This
  library's refusals echo neither, at any link of the cause chain, so the line a host
  logs is the *cause* and never the exception Integrant threw. It is the host's hazard
  and not this library's, which is why the fix is documentation and an assertion rather
  than anything in `src`. The same sentence has a second half: the caller must halt
  `(:system (ex-data e))` itself, or a boot that failed halfway leaks whatever came
  before the key that threw.
- **A session in a row puts the database in front of every request.** Once §8's store is
  wired, a request no longer merely *may* touch the database — it does, twice, because
  the CSRF token lives in the session. Everything downstream inherits that: a closed pool
  stops being a degraded mode and becomes a failed request, an anonymous visitor costs a
  row, and a health check can no longer report what it computes. None of it is visible
  from reading either library; all of it appeared the moment the two were put together
  (measured 2026-09-21, pinned in the demo's seam test).
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
  which closes its sockets, rather than silencing it. **This is that engine's, not
  JDBC's**: pgjdbc 42.7.13 against PostgreSQL 18.6, through a proxy told to go quiet with
  both sockets open, answered `ready?` false in 2005 ms for a two-second timeout and
  failed a fresh boot in 2005 ms for a 1000 ms pool timeout (measured 2026-09-20). So a
  `ready?` that hangs is a fact about the driver in front of you, and the only way to know
  is to silence it and watch.

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

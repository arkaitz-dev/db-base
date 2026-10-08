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
proof 2026-09-20, §10's Integrant key the same day, §8's session store 2026-09-21, and
§10's two amendments — `collision/arbitrate!` and the `testing` namespace — 2026-09-25.**
Everything the specification asks for is written and tested. **Released as
`dev.arkaitz/db-base 0.1.0` on Clojars 2026-09-26** (tag `v0.1.0` at `fe535b1`, verified
from an isolated Maven repo), and the repository is public; **0.2.0 on 2026-09-28**, with
the store refusing a boot without its table and `testing/sessions`/`session`; **0.2.1 and
0.2.2 on 2026-09-28** — the over-long refusal's `:config-key`, and
`dev.arkaitz.db-base.native` for GraalVM images (0.2.2 fixes 0.2.1's doubled module
prefix on GraalVM CE 25.0.4); **0.3.0 on 2026-09-29**, `dev.arkaitz.db-base.web`, this
library as a web-base plugin (SPEC §10, rule 3 amended by the user); **0.4.0 on
2026-09-30**, `:libraries` — a library's migrations run beside the host's (SPEC §7); **0.5.0 on
2026-10-06**, the web plugin passing web-base 0.15.0's `:session :renew` through. **0.6.0 on 2026-10-08**, `testing/in-flight` (booking B24). Every public var of `src`
is pinned by name in `structure_test`'s `accepted-publics`, so a new one reds until it is
listed there with its reason.

**The second consumer arrived 2026-09-22** and with it §12's exit condition, which is
the sentence this repository exists to be judged by. It is not met: `demo-tasks/` uses
§8 for a feature a sealed cookie cannot serve, with a control that proves the
difference. The verdict is narrower than a vindication and is written out in §12 — read
it before adding anything, because the case rests on the store's *contract* and not on
the line count.

Commands go in this file **only once they have actually been run and observed to
work**, never from convention. Observed:

    clojure -M:test                        # whole suite; prints "Ran N tests containing M assertions."
    clojure -M:test -n <namespace>         # one namespace
    clojure -M:test -v <namespace>/<var>   # one test
    clojure -M:demo [port]                 # the first host, serving; the port is optional
    clojure -M:demo-test                   # its acceptance test
    clojure -M:demo-tasks [port]           # the three-library host; the port also moves the link's origin
    clojure -M:demo-tasks-test             # its acceptance test, with the cookie control
    clojure -M:demo-ledger [port]          # shared expenses on all three libraries (3002)
    clojure -M:demo-ledger-test
    clojure -M:demo-events [port]          # events with a capacity and a waiting list (3003)
    clojure -M:demo-events-test
    clojure -M:pg-test                     # the production engine, opt-in: needs the container below
    clojure -M:build-test                  # the release guards, against real git
    clojure -T:build jar                   # target/db-base-<version>.jar, src only
    clojure -T:build deploy                # to Clojars with CLOJARS_USERNAME/CLOJARS_PASSWORD; refuses a dirty, unpushed or tagged tree
    clojure -T:build verify-release        # after deploy: the jar on Clojars, byte for byte against the tag

**Releasing across the set**, in this order, since each consumer names the one before it:
web-base, then auth-base (its `deps.edn` declares web-base), then db-base, then the
consumers — db-base's hosts, auth-base's demo, and the template, whose CHANGELOG gets an
entry. For each library: commit and push, `clojure -T:build deploy` (the user's),
`clojure -T:build verify-release`, then bump its consumers the same day.

The PostgreSQL suite needs a server and `pg.local.edn` (gitignored) naming it. Observed
2026-09-26, with Apple's `container` CLI 1.4.1 (Docker is not installed here):

    container system start --enable-kernel-install
    container run -d --name db-base-pg -e POSTGRES_PASSWORD=<generated> -e POSTGRES_DB=db_base_test \
      -p 127.0.0.1:55432:5432 docker.io/library/postgres:18.6

and `pg.local.edn` holds `{:jdbc-url "jdbc:postgresql://127.0.0.1:55432/db_base_test"
:user "postgres" :password "<generated>"}`. Without that file every test there fails
saying so — a run with nothing to run against is never a pass. Each test works in a
schema of its own and drops it. Run it before any release: it is the only thing that
sees what the everyday suite cannot, and it found §8's column on its first run.

**No demo may ever reach `:test`** — `demo/`, `demo-tasks/`, `demo-ledger/`, `demo-events/`,
and `hosts-test/`, the one support namespace the three last share on their `-test` aliases: `structure_test`'s logging-backend scan reads
the running JVM's classpath, and the web stack brings logback. Its own docstring says a
backend arriving through a test extra "reds falsely but visibly, and the fix is to move
it, never to filter the scan". They are eight aliases and five directories, and nothing a
consumer receives changes because any of them exists.

**The demos run on a JVM only.** They carry neither the `native` namespaces of web-base
and db-base nor the asynchronous logback of a production host: those live in the host
template (`../app-template`), which builds and verifies native images. A demo is where a
library's contract is exercised, not a deployment recipe.

Every host consumes **web-base 0.15.0 and auth-base 0.14.0 as releases** (2026-10-08),
which carry what the hosts found missing — `FRICTION.md` says which entry each closed —
and installs **both libraries as web-base plugins**: `db-base.web/plugin` for the
session store and `/health`, `auth-base.web/plugin` for the standard sign-in and
sign-out, with no login view, logout form or probe of the host's own. A host signs
people in through auth-base's optional `jdbc` store, has its tables migrated from
auth-base's jar through `:libraries` (since 2026-10-01; no copies), and calls its `check!`
at boot.

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
      PostgreSQL-shaped mistake in silence. §8's types had been measured across five
      engines — but H2's PostgreSQL mode stood in for PostgreSQL, and the real engine
      refused `CLOB` the first time the opt-in `:pg-test` suite ran (2026-09-26); see the
      trap below. What the decision unblocks is auth-base's
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
- [x] **§12's gate was satisfied in form and not in substance** (recorded with the user
      2026-09-21) — **closed 2026-09-22 by the second consumer.** `demo-tasks/` lists
      where somebody is signed in and ends ONE session while the others keep working,
      with a control that boots the same host over a sealed cookie and asserts the
      opposite. **And §12's own dichotomy turned out to be false**: that feature needed
      neither a listing surface here nor the host reading `db_base_sessions`. Ring puts
      `:session/key` on every request, so the host keeps its own `device` table and ends
      one through `delete-session`, which is Ring's port. The third door was there all
      along. Both the old claim and its refutation are in §12 — read them together
      before re-deciding anything about §8.

- [x] **The host template**, 2026-09-28: `../app-template`, a deps-new template
      (`dev.arkaitz/app`) cut from these hosts, minimal by the user's choice — sign-in,
      one gated page, /health, sessions in a row, a sweeper for sessions and challenges.
      `bin/verify` there generates two projects and runs their suites. Published as a
      **private** repository, `arkaitz-dev/app-template`, while it is iterated on.
- [x] **A host of all three libraries**, 2026-09-22: `demo-tasks/`, a CRUD application
      with magic-link sign-in over web-base, auth-base and this library. It writes the
      adapter rule 3 forbids here — auth-base's five-method `Store` over the datasource
      this library hands out, 48 lines — and its `take-challenge!` is proved single-use
      by **forcing** an interleaving rather than racing for one: a `DataSource` proxy
      parks the first caller inside its DELETE while another runs to completion. Two
      controls make that mean something and both are mutation-tested: a naive store must
      produce two winners, and the harness must be shown to have suspended somebody.

- [x] **auth-base's login form bounds the identifier before normalising it** — **fixed in
      auth-base 0.5.1** (`b6ef21f`, 2026-09-28), with the default normal form made
      independent of the JVM's locale (`2d64192`: under tr_TR it had turned `ADA@IX.TEST`
      into `ada@ıx.test`). Deployed and verified 2026-09-28; the hosts are on it.
      web-base's case conversions were audited 2026-10-01: every one in `src` passes
      `Locale/ROOT`. Originally (found
      2026-09-28 by the Phase-3 panel on the hosts' bounds). `handlers.clj` `submitted?`
      counts the raw value against 320, and the ceremony then lower-cases it: "İ" becomes
      two characters, so a 320-character address can reach `login_challenge.identifier`
      at up to 640 — a 500 on PostgreSQL, stored whole on SQLite. The hosts' own invitation
      form was fixed the right way (bound after normalise); this one is auth-base's, and
      needs a release. Low severity: it takes a crafted address and fails loud.
- [ ] **Two auth-base threads this host opened and did not close**, both needing to be
      raised there rather than fixed from here:
      - ~~**A magic link fetched by a corporate link scanner is consumed before the human
        clicks it.**~~ — **closed by auth-base 0.8.0 (`950d3ed`, 2026-09-29)**: opening a
        link renders a confirmation page whose POST, behind the host's CSRF, redeems; no
        GET spends a link or signs anyone in. The hosts render the fifth view state and
        `hosts.support/open-link!` walks it as a browser does.
      - ~~**The subject a host returns from `:on-unknown` must be `=` to what
        `subject-for` answers afterwards**~~ — **enforced since auth-base 0.4.0
        (`5120db8`)**: `redeem!` asks the store once more at registration and refuses a
        hook whose answer it does not give back, so a session no revocation could end
        can no longer be born.

- [x] **`FRICTION.md`: nine findings from two more hosts** (`demo-ledger/`,
      `demo-events/`, 2026-09-25) — **all nine acted on the same day**, at the user's
      request; its "Resolved" table says where. This library's three (F2, F6, F7) are
      README recipes and docstrings (`325c887`); the rest shipped as web-base 0.3.0 and
      auth-base 0.2.0, and all four hosts moved onto them (`b024851`, `efd809b`,
      `35a7e85`). Also closed there: §11's open row, whether `testing` earns a second
      consumer — it earned two.

- [x] **§7's lock release is scoped by holder, and the failure path now proves it**
      (2026-09-25). The panel of 2026-09-21 was half right: the success path was already
      pinned by `a-boot-gives-back-its-own-lock-row-and-no-other`, but `release-after-failure!`
      could delete by id alone, or every row, with the whole suite green.
      `a-boot-that-fails-gives-back-its-own-lock-row-and-no-other` parks a boot holding the
      lock, plants the row a second instance would hold after the repair, and lets the first
      fail at 002-b: both mutants die there and nowhere else.

Two belong to **web-base**, found by building the demo against it and measured
2026-09-21. Neither is db-base's to fix and both are worth raising there:

- [x] **Its error handler sits inside its session middleware** — **answered
      2026-09-26 by web-base 0.4.0 (`5a5116d`)**: `/health` is now `:sessionless`,
      mounted before the session, and every host uses it; `demo`'s seam test sends a
      probe carrying a session cookie on a closed pool and gets the 503. **And the
      boundary itself moved in web-base 0.6.0 (`6e4af56`)**: a session store that
      throws is the base's 500 page on every route, never a raw exception at the
      adapter.
      Originally: **its error handler sits inside its session middleware.** `web_base.clj` wraps
      `ring-handler` with `error/default-handler` innermost and applies `session/wrap`
      later, so with a server-side store a database that is down makes a request die at
      the adapter instead of reaching the error page. The measured consequence here: the
      demo's `/health` computes a 503 that never leaves, and `ready?` became unobservable
      through the stack — a route that never calls it survives the whole suite.
- [x] **Every request without a cookie writes a session row** — **fixed 2026-09-26
      in web-base 0.4.0 (`ce077b0`)**: the CSRF token is written only when a request
      used it, so a probe, JSON or a redirect writes nothing; only a page that renders
      a form costs a row. Pinned in every host (`a-health-probe-leaves-no-session-row`).
      Originally: **every request without a cookie writes a session row**, because ring-anti-forgery
      keeps its token in the session. Three health probes, three rows; a balancer polling
      every five seconds writes seventeen thousand a day, and §8's rows are reclaimed only
      by a function the operator calls. A route that wants no session has no way to say so.

- [x] **"Its stack has no injection point"** — **closed 2026-09-27, and half of it was
      wrong.** `auth/wrap-revoked` works under web-base as reitit route `:middleware`,
      which runs inside the session layer (probe: after `revoke!` the next request
      deleted the row); auth-base's README shows it (`20bf0bd`). The other half —
      `wrap-subject` computing the subject on every route — was decided by a
      five-lens panel to stay eager and be documented (`54d0c8e`): one indexed read,
      9.7 µs measured, where revocation takes effect; a lazy `delay` would be truthy
      and open every gate, an accessor would touch ~30 host sites, a per-route opt-out
      leaves the default 404 without a subject, and a cached generation changes what
      revocation means. The panel's side finding became web-base 0.6.0's error
      boundary.

Threads that belong to **auth-base**. Two were closed on 2026-09-22 by building the
host that needed them; the third is untouched:

- [x] `auth-base/SPEC.md` contradicted §8 about who ships the JDBC implementation of its
      Store port, and called this project `base-db`. **Both corrected there 2026-09-22**,
      settled by construction rather than argument: the host writes it, `demo-tasks/` is
      the first that has, and "a page of code" turned out to be 48 lines plus 81.
- [x] **A second identifier for an existing subject — its first slice, a second email —
      shipped in auth-base 0.13.0 (2026-10-07)**, designed with the user and a two-stage
      panel (auth-base SPEC §18). The addresses page and the attach link, a key holding
      every identifier, the primary notified of each change, and recency to change any;
      the template uses it (0.3.0). A mobile, which needs an SMS sender, is a later slice.
- [x] auth-base's `take-challenge!` as one `DELETE … RETURNING`. **Written and measured
      2026-09-22** — in the host, as rule 3 requires. `RETURNING` works on SQLite through
      xerial 3.53.4.0, and single use is proved by *forcing* an interleaving rather than
      racing for one: a `DataSource` proxy parks the first caller inside its DELETE while
      another completes. A barrier alone would not have done it — SQLite serialises
      writers at the file, and a naive store passes that every time on this machine,
      measured, with the naive store kept as the control that must show two winners.

- [x] **auth-base's `429` answered `Retry-After: 60` whatever the configured window
      was.** Fixed there 2026-09-25 (`026b9fc`, `5f6d867`, `d14a3b7`): the header is now
      the whole seconds until that source's window reopens, rounded up, and a host's own
      limiter gets none. Verified in the browser against `demo-tasks`: a refusal a
      minute into the fifteen-minute window said `840`, exactly the ceiling of what the
      server log gives.

Two more were opened there in the same work and are recorded above under the second
consumer: the link scanner that burns a magic link before its recipient clicks it, and
the obligation that `:on-unknown`'s return must equal what `subject-for` answers after.

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
a sibling module of ours — except the root.** Ring's session store is three functions
old enough to trust. auth-base's store is ours, and an implementation here would lock
two of our libraries to each other's releases in both directions. **Amended 2026-09-29
by the user's decision** (SPEC §10): web-base is the root the set is built on, and
`dev.arkaitz.db-base.web` is this library as its plugin — optional, web-base undeclared
like ring, and the only namespace a scan lets name it. auth-base remains a sibling and
remains forbidden.

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
- **`take-challenge!` implemented as read-then-delete.** Single use is the whole
  security of a link that travels by email, and two statements have a window between
  them. **Written 2026-09-22 in `demo-tasks/`** — in the host, as rule 3 requires — as
  one `DELETE … RETURNING`, so the delete is what decides and the engine serialises it.
  **Since 2026-09-25 it lives in auth-base's optional `jdbc` namespace** (F1), portable
  because a library cannot know the engine: a read, then a `DELETE` whose update count
  says who won — the read never decides.
  The trap survived into the *test* rather than the code: two threads and a barrier are
  green against the naive version every time on this machine, because SQLite serialises
  writers at the file. What catches it is **forcing** the interleaving with a
  `DataSource` proxy that parks the first caller inside its DELETE, plus keeping the
  naive store as a control that must show two winners.
- **The session store written as an upsert**, or as update-then-insert-if-zero. Ring
  never asks for one, and re-inserting a row whose update touched nothing resurrects a
  session someone revoked. Ring's own `MemoryStore` upserts, so the reference
  implementation is the wrong answer here. SPEC §8.
- **A migration run that applies zero migrations reporting success.** An empty or
  misspelt directory is the schema one deploy behind. Same shape as a test run that
  ran no tests: the count is the signal.
- **A sweeper on a timer.** A background thread is a lifecycle the host did not ask
  for and a shutdown path that gets forgotten. SPEC §9.
- **An engine's compatibility mode is not the engine.** §8's `data CLOB` passed H2 in
  PostgreSQL mode and was refused by PostgreSQL 18.6 itself (`type "clob" does not
  exist`), so every host on the production engine with sessions on could not boot —
  found 2026-09-26 by the first run of `clojure -M:pg-test`, never by the everyday suite,
  which cannot see it. The column is now a bounded `VARCHAR` the store enforces in
  Clojure, with `:dialect :postgresql` for unbounded `TEXT` (SPEC §8). A claim about an
  engine stands on that engine, run.
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
- **A shipped library migration edited in place.** The session table's migrations —
  `001-sessions`, `001-sessions-postgresql` — are recorded in `db_base_migrations` by id
  alone: §7 compares ids and keeps no checksum. Changing the SQL under an existing id is
  therefore skipped in silence by every database that already applied it, and only a
  fresh one sees the new table: two shapes of `db_base_sessions` in production, with
  nothing that says so. A change to a shipped migration is a new id, always; a rename
  or a removal is already refused loudly, as a recorded id the source has lost.
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
  wired, a request no longer merely *may* touch the database — it does. Everything
  downstream inherits that: a closed pool stops being a degraded mode and becomes a
  failed request, an anonymous visitor costs a row, and a health check can no longer
  report what it computes. None of it is visible from reading either library; all of it
  appeared the moment the two were put together (measured 2026-09-21, pinned in the
  demo's seam test).

  **Corrected 2026-09-22 by the second host:** "it does, twice" described the *anonymous*
  path — a read that misses and an insert. An authenticated page in steady state is two
  READS and **zero writes**, because ring-anti-forgery returns the response untouched
  when the token it would set is the one already there, so nothing reaches
  `write-session`. `demo-tasks`'s seam test pins the zero by watching the row's expiry,
  which is computed when a row is written and therefore does not move when none is.
  Adding auth-base makes the second read a `generation` lookup on every request, gated
  route or not.
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

**`arbitrate!` is the near-miss to measure the next one against** (2026-09-25). It came
in as two functions of the host's and no SQL, and the shape it was chosen over —
`insert-or-read!` over SQL vectors — would have published a statement runner and sat one
parameter away from update-or-insert, the upsert §8 exists to refuse. §10's *Out* now
says it in one sentence: a function here that accepts a statement from the host is §9's
first entry, whatever it is called. The `testing` namespace is the other door: it is for
tests, and the day it grows a helper a request path would want, it has become the
erosion.

## The honest state of this repository

SPEC §12 argues that this library may not deserve to exist, and states the condition
under which it should be deleted: if after a second consumer it is still nothing but
the wiring of §6 and §7, fold it back into the applications. That paragraph is not
decoration. Read it before adding anything.

**The second consumer arrived 2026-09-22 and the condition is not met**, which is a
smaller claim than it sounds and §12 says so at length. §6 and §7 remain wiring anybody
could write. What two hosts have now shown is that **§8 is a contract at least one real
feature depends on** — and, separately, that §12 was *wrong* about how that feature
would have to be built: it insisted on a listing surface here or the host reaching into
`db_base_sessions`, and there was a third door neither. The refutation is left beside
the claim on purpose. A falsification section that only records the times it was right
is not one.

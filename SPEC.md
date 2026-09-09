# db-base — specification

> **Coordinates.** Directory `db-base` · repository `arkaitz-dev/db-base` · artifact
> `dev.arkaitz/db-base` on the day there is one.
>
> **Status.** Specification. No code. Written 2026-09-09.
>
> **Provenance.** The third of three: `web-base` serves HTTP, `auth-base` turns
> someone into a subject, and this opens the connection both of them are careful not
> to open themselves. web-base §2 says it *never opens a database*; auth-base §7 says
> storage is a port with an in-memory default. This is what the host plugs into those
> holes when it has a real database, and **§12 argues honestly about whether it
> deserves to exist at all.**

## 1 · What it is

**A pooled connection with a lifecycle, migrations that run before anything serves,
and the decisions behind both.**

It is a **library you call**, and mostly it is a **component you start and stop**, the
same shape web-base's server has: a map goes in, a handle comes out, and `stop` closes
what `start` opened.

## 2 · What it is not

- **Not a query language.** No DSL, no threading macro over SQL, no opinion on
  HoneySQL. `next.jdbc` already takes a datasource and a vector.
- **Not an ORM or an entity mapper.** It never learns what a row means.
- **Not a schema.** The host's tables are the host's, including their names.
- **Not a database.** The engine is the host's choice and its driver is the host's
  dependency.
- **Not a repository pattern.** A function that takes a datasource is not improved by
  being wrapped in a protocol first.

## 3 · The rule that governs everything here

**Impose what you are, not what you use.** web-base §10 settled this and it binds here
harder, because a data layer is where transitive dependencies breed. A consumer of this
library must receive a connection pool and a migration runner, and **not** a JSON
codec, a logging backend, a date library or an opinion about their query builder.

Two consequences that decide the whole design:

**The engine is not chosen here.** Any JDBC database. The host brings the driver, and
the host writes SQL for the engine it brought. Nothing in this library may be
PostgreSQL-shaped, which is a discipline rather than a preference: the moment one
function assumes `jsonb` or `LISTEN`, the library belongs to one engine.

**It may implement a port defined by a stable third party. It must never depend on a
sibling module of ours.** Ring's session store protocol lives in `ring-core`, is three
functions long, has not changed in a decade, and is already on every relevant
classpath — implementing it costs nothing and imposes nothing. auth-base's store port
is *ours*, versioned with us, and an implementation living here would lock two of our
own modules to each other's releases in both directions. That adapter belongs in
auth-base or in the host, and §8 shows how small it is.

## 4 · The membership test

**Would a bicycle rental and a clinic's appointment book need this, unchanged?** Both
open a pool at boot, run migrations before serving, and close it on the way out.
Neither needs a query builder chosen for them.

## 5 · The boundary

**It owns**

- the datasource: built from configuration, pooled, started and stopped;
- migrations: found where the host says, applied in order, before anything serves;
- a readiness answer: whether the database is actually reachable;
- **its own** tables, when it ships an implementation of a published port, and only
  those.

**It receives**

- configuration, as a map, already resolved. It never reads a file, never reads an
  environment variable, and never looks for either. web-base learned that lesson
  explicitly: a library that knows a filename can look for it, and then the directory
  a process started from decides which database it opens.
- a place to find migrations.

**It never learns**

- what a table means, what a row is, or what the host calls anything;
- which engine it is talking to, beyond what JDBC exposes.

## 6 · The datasource as a component

```clojure
(def db (db/start {:jdbc-url "jdbc:…" :user … :password … :pool {:max 10}}))
;; => {:datasource … :migrations-applied n}
(db/stop db)
```

`start` fails loudly and names the key when configuration is missing or malformed,
which is web-base's rule and earns its keep the same way: a pool that silently defaults
to something is a production incident with no symptom in development.

**A password is never defaulted and never generated.** It arrives or the call fails.
The same reasoning as web-base's session key: a value invented at startup works
beautifully until it does not, and differs per instance.

`stop` closes the pool. A component that cannot be stopped cannot be restarted, and a
REPL that cannot restart is a REPL nobody uses.

### Where that map comes from

§5 says the configuration arrives resolved. This says where it is resolved from,
because a rule about what a library may not read says nothing about where an operator
should put a password.

**The connection details live in an EDN file that is not in the repository.** It is the
traditional `.env` in another format, and it is where web-base already keeps its signing
key. `*.local.edn` is git-ignored in all three modules, which is what makes the
statement true rather than aspirational. The password sits in that file literally, and
that is the right place for it: what a password may never do is travel with the
repository, be invented at startup, or come out of a file this library went looking for.

**The host reads it; this library does not.** A host that already uses web-base has its
readers and the `#wb/env` tag; one that does not reads the file with `clojure.edn` in
three lines. Either way what reaches `start` is the map of §5, and this library never
learns a filename. auth-base draws the same line for its bootstrap list — it arrives as
data the host passes in — and drawing it is exactly what lets both modules compose with
web-base without either depending on it.

## 7 · Migrations

**They run at boot, before the first request, and the process does not serve if they
fail.** A database whose shape is one deploy behind the code is a corruption that
reports itself as a bug somewhere else entirely.

What this library owns is the *running*, not the *writing*: it finds a directory the
host names, applies what has not been applied, in order, once, recording what it did.
The SQL inside is the host's and speaks the host's engine.

**Which migration library sits underneath is not settled here.** It is a real decision
with two or three defensible answers, and this workspace's rule is that tools are
recorded once they have been run and observed, never chosen from convention.

## 8 · The session store it ships

This is the reason the library is obviously worth existing today, and it settles a
question that came up while auth-base was specified.

Ring's session store port is three functions — `read-session`, `write-session`,
`delete-session` — keyed by session id. web-base already uses that port and ships the
signed-cookie implementation, which cannot be revoked from the server. **A consumer
that needs to end a session from the server needs a stored one**, and that
implementation is small enough to state completely: one table of key, data and expiry;
write generates the key when it is nil; delete removes the row; expired rows are swept.

It ships **here**, with its own migration, in its own namespace, because it implements
a third party's port under rule §3 and because its table is this library's own rather
than the host's.

**auth-base's store is a different case and does not ship here.** Its protocol is ours,
and the adapter is a page of code the host writes over a datasource this library
already gave it:

```clojure
(defrecord JdbcStore [ds]
  auth/Store
  (take-challenge! [_ token] …)   ; one statement, returning and deleting atomically
  …)
```

That page belongs where the protocol is, or in the application. It does not justify
binding two of our libraries to each other's version numbers.

## 9 · What it must never own

Written down before it is tempting, because each of these has a plausible first step:

- **A query builder.** "Just a small helper for `where`" is how one arrives.
- **Entity mapping.** The moment a row becomes a record with a namespace, the library
  knows the domain.
- **A connection-per-request middleware.** That is the host's transaction boundary to
  decide, not this library's, and deciding it here makes every consumer's request
  handling this library's business.
- **Read replicas, sharding, tenancy.** Real needs, all of them the host's.
- **A `db` global.** Ambient state is the rule web-base was built to avoid; a datasource
  in a var root is the same mistake with a connection attached.

## 10 · Scope

**In**: the pool and its lifecycle, migrations at boot, a readiness check, and
implementations of published third-party ports whose tables are its own.

**Out**: everything in §9, the engine, the schema, the SQL, and the driver.

## 11 · Settled, and open

**Settled**

| | |
|---|---|
| Engine-agnostic: any JDBC, the driver is the host's | §3 |
| Configuration arrives as a map; it never reads a file or an environment | §5 |
| The connection details live in an EDN outside the repository, read by the host | §6 |
| It may implement a third party's port, never depend on a sibling of ours | §3, §8 |
| Migrations run at boot or the process does not serve | §7 |
| A stored session store ships here, with its own table | §8 |

**Open**

- **The pool** and **the migration library** underneath. Both are real choices and
  neither is made from convention.
- **The sweep of expired sessions**: on write, on a timer, or by the operator.
- Whether a **readiness check** belongs here or is one line in the host.

## 12 · The honest argument against this library

It is the thinnest of the three and it may not deserve to exist.

Wiring a pool is thirty lines. Running migrations is twenty more. A library whose whole
content is fifty lines of wiring is a decision record with code attached, and a decision
record can be a paragraph in the host instead.

The case in favour is that the *decisions* are the value and they are the part that
gets re-litigated: which pool, which migration tool, what the configuration looks like,
what happens when a migration fails at boot, whether the password may ever be
defaulted. Those are settled once here rather than argued in every project, and the
session store of §8 is real code that would otherwise be copied.

**The exit condition, so this is falsifiable.** If, after a second consumer, this
library is still nothing but the wiring of §6 and §7, then it should be folded back
into the applications and deleted. web-base §7's rule points the same way from the
other side: when in doubt, leave it in the consumer, because moving code *into* a
library later is cheap and getting it back out is not.

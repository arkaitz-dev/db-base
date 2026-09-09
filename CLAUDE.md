# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in
this repository.

`SPEC.md` says **what** to build and why. This file says **how to work here**, and
repeats only the few rules that get broken silently.

## What this is

`dev.arkaitz/base-db` — a pooled connection with a lifecycle, migrations that run
before anything serves, and the decisions behind both. The third of three:
`web-base` serves HTTP and never opens a database; `base-auth` turns someone into a
subject and takes storage as a port; this is what a host plugs into those holes.

## Project state

**Specification settled 2026-09-09; no code.** No build or test command is recorded
here, because none has been run. Commands go in this file **only once they have
actually been run and observed to work**, never from convention. That applies with
particular force to the two choices SPEC §11 leaves open, the pool and the migration
library: neither is picked from habit.

## The three rules that must survive contact with code

**1 · Impose what you are, not what you use.** A consumer must receive a pool and a
migration runner, and nothing else: no JSON codec, no logging backend, no opinion
about their query builder. A data layer is where transitive dependencies breed.

**2 · Nothing here may be PostgreSQL-shaped.** Any JDBC database. The first function
that assumes `jsonb`, `LISTEN` or `RETURNING` hands the library to one engine, and it
will be written by someone who only has that engine in front of them.

**3 · It may implement a port defined by a stable third party; it must never depend on
a sibling module of ours.** Ring's session store is three functions old enough to
trust. base-auth's store is ours, and an implementation here would lock two of our
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
  decides which database it opens.
- **A connection per request, decided here.** The transaction boundary is the host's.
  Deciding it here makes every consumer's request handling this library's business.
- **`take-challenge!` implemented as read-then-delete**, when base-auth's adapter is
  eventually written. Single use is the whole security of a link that travels by
  email, and two statements have a window between them.

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

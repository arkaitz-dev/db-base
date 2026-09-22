-- The host's own record of which sessions belong to whom, so that somebody can
-- see where they are signed in and end one of them without ending the rest.
--
-- **This table is why db-base needs no listing surface.** Its §12 argued the
-- feature could only be built by the library growing one — which §9 is a list of
-- reasons against — or by the host reading `db_base_sessions`, which §8 says is
-- the library's. There is a third door and this is it: Ring puts `:session/key`
-- on every request, so the host learns its own session's id without reading
-- anybody's table, keeps that id here beside whatever else it wants to remember,
-- and ends one by handing the id back to the store it built itself.
--
-- No foreign key to `db_base_sessions`, deliberately. That table is the
-- library's and it reserves the right to migrate it; a reference from here would
-- weld this host's schema to a shape neither library agreed to share.
--
-- The cost of having no foreign key is that nothing cascades, so this host has
-- to keep its own records in step by hand. It does, on **both paths a person
-- controls**: logging out forgets this device, and revoking forgets all of
-- them. What remains — and it is now the only stale case — is a session that
-- simply **expires**, or one an operator reclaims: db-base deletes those rows
-- without telling anybody, and this table cannot know. So the list is what this
-- host last saw rather than what the library currently holds, and ending a
-- device the library has already forgotten is harmless.
--
-- That narrowing was not designed, it was observed: the first person to use the
-- running application logged out, and the next look at the database showed a
-- device naming a session that no longer existed.
CREATE TABLE device (session_id VARCHAR(36) NOT NULL PRIMARY KEY,
                     subject VARCHAR(36) NOT NULL,
                     user_agent VARCHAR(200) NOT NULL,
                     first_seen BIGINT NOT NULL)

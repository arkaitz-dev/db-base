-- What the application is actually for. `subject` is the owner, and every read
-- and every write in `demo-tasks.tasks` carries it in the WHERE clause rather
-- than checking it in a handler: the next caller of those functions — a second
-- route, a batch job, an API — will not have the handler's guard.
--
-- No foreign key to `account`, and none to db_base_sessions either. The second is
-- the one that matters: that table belongs to db-base's §8, which reserves the
-- right to migrate it, and a host schema that referenced it would weld the two
-- together in a direction neither library agreed to.
CREATE TABLE task (id VARCHAR(36) NOT NULL PRIMARY KEY,
                   subject VARCHAR(36) NOT NULL,
                   body VARCHAR(200) NOT NULL,
                   done INTEGER NOT NULL,
                   created_at BIGINT NOT NULL)

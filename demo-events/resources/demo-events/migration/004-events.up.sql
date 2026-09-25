-- An event with a capacity. `going` counts the confirmed places and is the arbiter of
-- the capacity: taking a place is `UPDATE … SET going = going + 1 WHERE going <
-- capacity`, one statement every engine serialises on the row, so two people cannot
-- both take the last place. The CHECK is the schema saying the same thing.
CREATE TABLE event (id VARCHAR(36) NOT NULL PRIMARY KEY,
                    owner VARCHAR(36) NOT NULL REFERENCES account (subject),
                    title VARCHAR(120) NOT NULL,
                    capacity INTEGER NOT NULL CHECK (capacity > 0),
                    going INTEGER NOT NULL CHECK (going >= 0 AND going <= capacity),
                    starts_at BIGINT NOT NULL)

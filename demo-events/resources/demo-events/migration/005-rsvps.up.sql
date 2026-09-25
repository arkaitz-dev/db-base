-- One answer per person per event: going, or waiting in the order they asked. Removing
-- an event removes its answers, which on SQLite needs `foreign_keys=true` in the URL.
CREATE TABLE rsvp (event_id VARCHAR(36) NOT NULL REFERENCES event (id) ON DELETE CASCADE,
                   subject VARCHAR(36) NOT NULL REFERENCES account (subject),
                   status VARCHAR(8) NOT NULL CHECK (status IN ('going', 'waiting')),
                   joined_at BIGINT NOT NULL,
                   PRIMARY KEY (event_id, subject))

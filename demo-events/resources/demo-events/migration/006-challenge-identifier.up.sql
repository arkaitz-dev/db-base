-- auth-base 0.8.0's index on the challenges' identifier, copied from `auth-jdbc/ddl`:
-- a revocation now drops the subject's unused links, and finds them by identifier.
CREATE INDEX login_challenge_identifier ON login_challenge (identifier)

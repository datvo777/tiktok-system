-- Lock table for ShedLock, so a scheduled job runs on one instance at a time. Only the
-- upload cleanup job uses it today. Column names and types are what the JDBC provider expects:
-- plain TIMESTAMP, because it writes UTC wall-clock values and a TIMESTAMPTZ column would
-- reinterpret them in the connection's time zone.
CREATE TABLE upload.shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);

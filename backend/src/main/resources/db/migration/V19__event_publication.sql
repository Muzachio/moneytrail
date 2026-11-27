-- Spring Modulith's event publication registry: the outbox of ADR-0005 and ADR-0014. An event of a
-- module listened to with @ApplicationModuleListener gets a row here, written in the transaction that
-- publishes it, and the row goes when its listener completes (completion-mode delete in
-- application.yml), so the table holds only what is still to be delivered.
--
-- Spring Modulith reads and writes the table, but Flyway creates it, like every other table
-- (spring.modulith.events.jdbc.schema-initialization stays off). The columns are those of Spring
-- Modulith 2.2 for PostgreSQL (schemas/v2/schema-postgresql.sql in spring-modulith-events-jdbc): an
-- upgrade that changes them needs a migration of its own.
CREATE TABLE event_publication (
    id                     uuid        PRIMARY KEY,
    listener_id            text        NOT NULL,
    event_type             text        NOT NULL,
    serialized_event       text        NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamptz
);

-- Completing a publication looks it up by its listener and serialized event.
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);

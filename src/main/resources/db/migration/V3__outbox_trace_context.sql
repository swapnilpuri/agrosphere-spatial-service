-- Transport metadata only: existing versioned event envelopes remain unchanged.
ALTER TABLE outbox_event ADD COLUMN trace_parent varchar(55);
ALTER TABLE outbox_event ADD COLUMN trace_state varchar(512);

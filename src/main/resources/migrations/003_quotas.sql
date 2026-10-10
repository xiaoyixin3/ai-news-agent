-- @statement
CREATE TRIGGER sources_quota BEFORE INSERT ON run_sources
WHEN NOT EXISTS(SELECT 1 FROM run_sources WHERE run_id=NEW.run_id AND source_id=NEW.source_id)
 AND (SELECT COUNT(*) FROM run_sources WHERE run_id=NEW.run_id)>=100
BEGIN SELECT RAISE(ABORT,'SOURCE_COUNT_LIMIT'); END;
-- @statement
CREATE TRIGGER events_quota BEFORE INSERT ON events
WHEN (SELECT COUNT(*) FROM events WHERE run_id=NEW.run_id)>=256
BEGIN SELECT RAISE(ABORT,'EVENT_COUNT_LIMIT'); END;
-- @statement
CREATE TRIGGER event_metadata_quota BEFORE INSERT ON events
WHEN COALESCE((SELECT SUM(length(CAST(COALESCE(payload,'') AS BLOB))+length(CAST(summary AS BLOB))+512) FROM events WHERE run_id=NEW.run_id),0)
 +length(CAST(COALESCE(NEW.payload,'') AS BLOB))+length(CAST(NEW.summary AS BLOB))+512>262144
BEGIN SELECT RAISE(ABORT,'EVENT_METADATA_LIMIT'); END;

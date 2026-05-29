-- Manual SQL to align document_node_artifacts.status with the Java enum.
-- Needed for existing databases that still have a check constraint without RATE_LIMITED.

ALTER TABLE document_node_artifacts
    DROP CONSTRAINT IF EXISTS document_node_artifacts_status_check;

ALTER TABLE document_node_artifacts
    ADD CONSTRAINT document_node_artifacts_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED', 'RATE_LIMITED'));

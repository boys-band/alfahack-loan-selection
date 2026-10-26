-- Owner-only migration. Retain dependency results even after replacing current pointers.
CREATE TABLE workflow_results (
    job_id uuid PRIMARY KEY REFERENCES workflow_jobs(id),
    application_id uuid NOT NULL REFERENCES applications(id),
    data_version bigint NOT NULL,
    step text NOT NULL,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (application_id, data_version)
        REFERENCES application_data_versions(application_id, data_version)
);
CREATE INDEX workflow_results_version_idx ON workflow_results(application_id, data_version, recorded_at);
CREATE TRIGGER workflow_results_immutable BEFORE UPDATE OR DELETE ON workflow_results
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
GRANT SELECT, INSERT ON workflow_results TO applications_app;

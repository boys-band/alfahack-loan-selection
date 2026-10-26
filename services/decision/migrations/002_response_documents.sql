-- Immutable read projections and action input for business-key replay.
BEGIN;
CREATE TABLE decision_documents (
    decision_id uuid PRIMARY KEY REFERENCES decisions(id),
    body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object')
);
CREATE TABLE action_documents (
    action_id uuid PRIMARY KEY REFERENCES manual_review_actions(id),
    request_payload jsonb NOT NULL,
    body jsonb NOT NULL
);
CREATE TRIGGER decision_documents_immutable BEFORE UPDATE OR DELETE ON decision_documents
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER action_documents_immutable BEFORE UPDATE OR DELETE ON action_documents
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
COMMIT;

-- Global read-only staff registry keyset order; existing customer index stays unchanged.
CREATE INDEX application_staff_registry_idx ON applications (created_at DESC, id DESC);

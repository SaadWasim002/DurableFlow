CREATE TABLE workflow_definitions (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    version INTEGER NOT NULL,
    definition JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT workflow_definitions_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT workflow_definitions_version_positive CHECK (version > 0),
    CONSTRAINT workflow_definitions_definition_object CHECK (jsonb_typeof(definition) = 'object'),
    CONSTRAINT workflow_definitions_name_version_unique UNIQUE (name, version)
);

CREATE TABLE workflow_runs (
    id UUID PRIMARY KEY,
    workflow_definition_id UUID NOT NULL REFERENCES workflow_definitions(id),
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    input JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CONSTRAINT workflow_runs_status_valid CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT workflow_runs_timestamps_ordered CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (finished_at IS NULL OR (started_at IS NOT NULL AND finished_at >= started_at))
    )
);

CREATE INDEX workflow_runs_definition_created_idx
    ON workflow_runs (workflow_definition_id, created_at, id);

CREATE TABLE task_runs (
    id UUID PRIMARY KEY,
    workflow_run_id UUID NOT NULL REFERENCES workflow_runs(id),
    task_key VARCHAR(200) NOT NULL,
    task_type VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    input JSONB NOT NULL DEFAULT '{}'::jsonb,
    output JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CONSTRAINT task_runs_key_not_blank CHECK (btrim(task_key) <> ''),
    CONSTRAINT task_runs_type_not_blank CHECK (btrim(task_type) <> ''),
    CONSTRAINT task_runs_status_valid CHECK (
        status IN ('PENDING', 'READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')
    ),
    CONSTRAINT task_runs_timestamps_ordered CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (finished_at IS NULL OR finished_at >= COALESCE(started_at, created_at))
    ),
    CONSTRAINT task_runs_run_key_unique UNIQUE (workflow_run_id, task_key),
    CONSTRAINT task_runs_id_run_unique UNIQUE (id, workflow_run_id)
);

CREATE TABLE task_attempts (
    id UUID PRIMARY KEY,
    task_run_id UUID NOT NULL REFERENCES task_runs(id),
    attempt_number INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    input JSONB NOT NULL DEFAULT '{}'::jsonb,
    output JSONB,
    error_type VARCHAR(200),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT task_attempts_number_positive CHECK (attempt_number > 0),
    CONSTRAINT task_attempts_status_valid CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT task_attempts_timestamps_ordered CHECK (finished_at IS NULL OR finished_at >= started_at),
    CONSTRAINT task_attempts_task_number_unique UNIQUE (task_run_id, attempt_number),
    CONSTRAINT task_attempts_id_task_unique UNIQUE (id, task_run_id)
);

CREATE TABLE execution_events (
    id UUID PRIMARY KEY,
    workflow_run_id UUID NOT NULL REFERENCES workflow_runs(id),
    task_run_id UUID,
    task_attempt_id UUID,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    reason TEXT,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT execution_events_task_same_run
        FOREIGN KEY (task_run_id, workflow_run_id) REFERENCES task_runs(id, workflow_run_id),
    CONSTRAINT execution_events_attempt_same_task
        FOREIGN KEY (task_attempt_id, task_run_id) REFERENCES task_attempts(id, task_run_id),
    CONSTRAINT execution_events_attempt_requires_task
        CHECK (task_attempt_id IS NULL OR task_run_id IS NOT NULL),
    CONSTRAINT execution_events_to_status_not_blank CHECK (btrim(to_status) <> ''),
    CONSTRAINT execution_events_details_object CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX execution_events_run_occurred_idx
    ON execution_events (workflow_run_id, occurred_at, id);

CREATE INDEX execution_events_task_occurred_idx
    ON execution_events (task_run_id, occurred_at, id)
    WHERE task_run_id IS NOT NULL;

CREATE INDEX task_runs_active_idx ON task_runs (workflow_run_id, status)
    WHERE status IN ('READY', 'RUNNING');

CREATE INDEX workflow_runs_active_created_idx ON workflow_runs (created_at, id)
    WHERE status IN ('QUEUED', 'RUNNING');

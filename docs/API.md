# Workflow API walkthrough

Base URL: `http://127.0.0.1:8080`. Start PostgreSQL and the application using the README instructions. Send JSON bodies with `Content-Type: application/json`.

## Postman collection

Import [DurableFlow.postman_collection.json](../examples/postman/DurableFlow.postman_collection.json) into Postman using **Import → Files**. No environment or authentication is needed. The collection variable `baseUrl` defaults to `http://127.0.0.1:8080`; change it if you use another port.

Run the requests in their saved order, either individually or using the Collection Runner. The collection includes all six workflow/run/task endpoints and all three health endpoints. Successful creation responses automatically save `workflowId`, `runId`, and the first task's `taskId` as collection variables. Each workflow creation uses a unique name so rerunning the collection avoids a name/version conflict. Each request checks its expected HTTP status.

To inspect another task, change the collection's `taskId` to that task's UUID from **Get run tasks**. Runs currently stay `QUEUED`, and **Get task attempts** returns `[]`.

## curl commands (PowerShell)

Run these from the repository directory after starting the application. Use `curl.exe` explicitly in Windows PowerShell. Set the IDs below from the preceding responses; the Postman collection captures them automatically.

```powershell
$baseUrl = 'http://127.0.0.1:8080'

# Health endpoints
curl.exe -i "$baseUrl/actuator/health"
curl.exe -i "$baseUrl/actuator/health/liveness"
curl.exe -i "$baseUrl/actuator/health/readiness"

# Create a workflow (201); copy its response id into workflowId
curl.exe -i -X POST "$baseUrl/workflows" -H "Content-Type: application/json" --data-binary '@examples/workflows/three-task-chain.json'
$workflowId = 'PASTE_WORKFLOW_UUID'

# Read the workflow (200)
curl.exe -i "$baseUrl/workflows/$workflowId"

# Create a run (202); copy its response id into runId
curl.exe -i -X POST "$baseUrl/workflows/$workflowId/runs" -H "Content-Type: application/json" --data-binary '@examples/workflows/run-input.json'
$runId = 'PASTE_RUN_UUID'

# Read the run and its tasks (200)
curl.exe -i "$baseUrl/runs/$runId"
curl.exe -i "$baseUrl/runs/$runId/tasks"

# Copy a task's id (UUID, not taskKey) from the tasks response
$taskId = 'PASTE_TASK_UUID'
curl.exe -i "$baseUrl/tasks/$taskId/attempts"
```

The curl workflow file has the fixed name `report-demo`, version `1`. Creating it again returns 409; reuse its workflow UUID or edit the file's name/version. On Linux/macOS, use `curl` instead of `curl.exe` and assign shell variables with `baseUrl='...'`, `workflowId='...'`, `runId='...'`, and `taskId='...'`.

This feature creates immutable workflow versions and queued runs. Runs remain `QUEUED` until the sequential executor is introduced. Task types `echo`, `sleep`, and `fail` are accepted for the upcoming built-in handlers.

## 1. Create a workflow

`POST /workflows` returns **201 Created**, a workflow object, and `Location: /workflows/{id}`.

Try the saved three-task example from a second PowerShell terminal:

```powershell
$baseUrl = 'http://127.0.0.1:8080'
$workflow = Invoke-RestMethod -Method Post -Uri "$baseUrl/workflows" `
    -ContentType 'application/json' `
    -Body (Get-Content -LiteralPath 'examples/workflows/three-task-chain.json' -Raw)
$workflow | ConvertTo-Json -Depth 20
```

The example defines `prepare -> format -> save`. Each task has:

| Field | Meaning |
| --- | --- |
| `id` | Unique key within this workflow; starts with a letter, then letters/digits/underscores/hyphens |
| `type` | `echo`, `sleep`, or `fail` |
| `dependsOn` | An array containing its parent's task key; omitted or empty for the root |
| `input` | A JSON object; defaults to `{}` |

Workflow `name` is required, trimmed, and limited to 200 characters. `version` is a required positive integer. Supply 1-100 tasks, with task IDs no longer than 200 characters. One task or one connected linear chain is supported. Tasks may be submitted in any array order; the saved response orders them by dependency.

Validation rejects duplicate IDs, self/missing dependencies, cycles, disconnected chains, branching, multiple parents, and unsupported types. Unknown request fields and fractional versions are rejected instead of silently accepted.

Input references use an object containing only `$ref`:

```json
{"previous": {"$ref": "prepare.output"}}
```

The referenced task must be upstream in the chain. `{"$ref":"run.input"}` refers to the complete run input. References may appear inside nested objects or arrays. They are stored and validated now; the executor will resolve them later. Field-path references such as `prepare.output.message` are not supported yet.

The response contains `id`, `name`, `version`, normalized `tasks`, and `createdAt`. The UUID identifies this particular version. The same name can have another version with a different UUID; existing versions are preserved. Repeating the example with the same name and version returns **409 Conflict**. Reuse its saved ID or change the name/version for another definition.

## 2. Read the workflow

`GET /workflows/{id}` returns **200 OK** and the saved definition.

```powershell
Invoke-RestMethod -Uri "$baseUrl/workflows/$($workflow.id)" | ConvertTo-Json -Depth 20
```

The identifier is the workflow UUID, not its name. Unknown UUIDs return 404. Editing/updating a saved definition is not exposed; create another version instead.

## 3. Create a run

`POST /workflows/{id}/runs` returns **202 Accepted**, a run object, and `Location: /runs/{id}`.

```powershell
$run = Invoke-RestMethod -Method Post -Uri "$baseUrl/workflows/$($workflow.id)/runs" `
    -ContentType 'application/json' -Body '{"input":{"reportName":"demo"}}'
$run | ConvertTo-Json -Depth 20
```

The body is optional. Omit it or send `{}` for empty run input. A supplied `input` must be a JSON object.

In one transaction, this request saves:

1. A new workflow run in `QUEUED` state, pinned to the selected definition version.
2. Task runs with separate UUIDs: the root is `READY`, and dependent tasks are `PENDING`.
3. Initial execution events for the run and every task.

If any write fails, all these writes roll back together. No attempt is created before actual execution. Every accepted POST creates a separate run; request idempotency keys arrive in a later feature.

The response contains `id`, `workflowId`, `status`, `input`, `createdAt`, `startedAt`, `finishedAt`, and `tasks`. For this feature, execution timestamps and outputs remain null.

## 4. Read a run

`GET /runs/{id}` returns **200 OK** and the saved run with task details.

```powershell
Invoke-RestMethod -Uri "$baseUrl/runs/$($run.id)" | ConvertTo-Json -Depth 20
```

For the example, expect run status `QUEUED` and task states `READY`, `PENDING`, `PENDING`. The endpoint reads PostgreSQL, so the same IDs remain queryable after application restart.

## 5. Read the run's tasks

`GET /runs/{id}/tasks` returns **200 OK** and an array in dependency order.

```powershell
$tasks = Invoke-RestMethod -Uri "$baseUrl/runs/$($run.id)/tasks"
$tasks | Select-Object id,taskKey,type,status
```

Each task contains `id`, `runId`, `taskKey`, `type`, `dependsOn`, `status`, `input`, `output`, and execution timestamps. `taskKey` is the definition key such as `prepare`; `id` is that task's UUID within this particular run.

## 6. Read task attempts

`GET /tasks/{id}/attempts` returns **200 OK** and an array ordered by attempt number.

```powershell
$taskId = $tasks[0].id
Invoke-RestMethod -Uri "$baseUrl/tasks/$taskId/attempts"
```

Use the task-run UUID, not the definition key. The HTTP response is `[]` until the executor creates an attempt. An unknown task returns 404, which distinguishes missing tasks from known tasks with no attempts.

When attempts exist, each contains `id`, `taskId`, `attemptNumber`, `status`, `input`, `output`, `errorType`, `errorMessage`, `startedAt`, and `finishedAt`.

## Errors

Errors use Spring's Problem Details JSON format (`application/problem+json`). For example:

```json
{
  "type": "about:blank",
  "title": "Workflow version conflict",
  "status": 409,
  "detail": "A workflow with this name and version already exists",
  "instance": "/workflows"
}
```

| Status | Meaning |
| --- | --- |
| 400 | Invalid JSON, invalid fields/graph/references, or malformed UUID |
| 404 | Workflow, run, or task does not exist |
| 409 | Workflow name/version already exists, including concurrent requests |
| 500 | Database operation failed; SQL details are logged, not returned |

Run/task writes and events are transactional. Definition validation happens before a definition is written. Detailed history querying, retries, cancellation, scheduling, and execution are later features.

See the README for the existing health endpoints. The application remains local to `127.0.0.1`.

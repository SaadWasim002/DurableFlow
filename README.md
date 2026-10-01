# DurableFlow

A workflow execution engine built incrementally with Java and Spring Boot.

DurableFlow creates and executes validated linear workflows using one background worker. Local PostgreSQL stores definitions, runs, task outputs, attempts, and transition history. REST APIs let you submit work and inspect its saved results.

## Requirements

- JDK 21 or a compatible newer JDK. This setup was verified with JDK 24.0.2.
- Docker Desktop running with Linux containers, and Docker Compose.
- Network access for the first build to download Maven and dependencies.

Spring Boot is pinned to 4.1.1. The Maven Wrapper is pinned to Maven 3.9.12, so a separate Maven installation is optional. Java compilation targets release 21. See [Spring Boot's system requirements](https://docs.spring.io/spring-boot/system-requirements.html) for runtime compatibility.

## Run locally

From the project directory in PowerShell:

```powershell
docker compose up -d --wait postgres
.\mvnw.cmd spring-boot:run
```

PostgreSQL listens on `127.0.0.1:5433`; the application listens on `127.0.0.1:8080`. Flyway applies pending migrations before the application finishes starting. PostgreSQL must be available for startup to succeed. In a second terminal:

```powershell
curl.exe http://127.0.0.1:8080/actuator/health
```

Expect HTTP 200 and a JSON response containing `"status":"UP"`, which now includes database connectivity in the health check. Spring Boot may also include health group names. Stop the server with Ctrl+C; it allows up to 20 seconds for graceful shutdown.

If port 8080 is occupied, choose another port:

```powershell
$env:PORT = '8081'
.\mvnw.cmd spring-boot:run
```

Use the selected port in the health URL. Remove the override later with `Remove-Item Env:PORT`.

## Available endpoints

All paths use the application port (8080 by default). Spring Boot Actuator provides these endpoints; no custom health controller is needed.

| Method and path | Purpose |
| --- | --- |
| `GET /actuator/health` | Overall health, including a PostgreSQL connectivity check |
| `GET /actuator/health/liveness` | Whether Spring reports the application as alive |
| `GET /actuator/health/readiness` | Whether Spring reports the application as accepting traffic |

Overall health returns HTTP 200 with `"status":"UP"` when healthy and HTTP 503 when PostgreSQL is unavailable. Database failure checks can take up to the pool's connection timeout. Health details and credentials are not included in the response.

The liveness and readiness groups reflect application availability; this configuration does not add PostgreSQL to the readiness group. Use overall health when checking database connectivity.

| Method and path | Purpose | Success |
| --- | --- | --- |
| `POST /workflows` | Create an immutable workflow version | 201 |
| `GET /workflows/{id}` | Read its definition | 200 |
| `POST /workflows/{id}/runs` | Create a queued run and its task records | 202 |
| `GET /runs/{id}` | Read the run and its tasks | 200 |
| `GET /runs/{id}/tasks` | Read tasks in dependency order | 200 |
| `GET /tasks/{id}/attempts` | Read attempts, resolved input, outputs, and errors | 200 |

Follow the [complete API walkthrough](docs/API.md) for requests, response fields, validation rules, and errors. The [execution walkthrough](docs/EXECUTION.md) demonstrates success, failure, saved attempts, and restart behaviour. Try [three-task-chain.json](examples/workflows/three-task-chain.json) or [failing-chain.json](examples/workflows/failing-chain.json). Accepted runs start `QUEUED`, then execute automatically.

Import the [Postman collection](examples/postman/DurableFlow.postman_collection.json) to try every endpoint with automatically saved IDs. The walkthrough also includes [curl commands](docs/API.md#curl-commands-powershell).

## Build and run the JAR

```powershell
.\mvnw.cmd verify
java -jar target/durableflow-0.0.1-SNAPSHOT.jar
```

`verify` runs unit and integration tests and packages the application. Integration tests use Testcontainers to create isolated PostgreSQL 17 containers with random host ports, apply the real migrations, and remove the test containers afterward. Docker must be running; the Compose development database does not need to be running for tests. Test data is kept separate from your development database.

The 65 tests cover graph/reference validation, UTC connections, migrations, saved results, uniqueness, HTTP responses, concurrent requests/claims, actual background execution, resolved attempt inputs, stop-on-failure, duplicate outcome protection, and transaction rollback. Starting the JAR requires the Compose database to be running.

## Execution behaviour

The worker executes one task at a time, polling PostgreSQL with a default 250 ms delay after each task/poll. A successful task makes its successor ready. A failed task stops the run and marks unstarted tasks `SKIPPED`. The built-in handlers are `echo` (return input), `sleep` (wait `millis`, default 1000, maximum 60000), and `fail` (record a deliberate error). Input references are resolved from saved run input and successful upstream outputs.

Set `EXECUTION_ENABLED=false` to run the API without executing tasks. `EXECUTION_POLL_DELAY_MS` changes the polling delay. Use one enabled application instance for this feature. Queued/ready work resumes after restart; interrupted `RUNNING` attempts require the lease recovery feature planned later. See [execution details and configuration](docs/EXECUTION.md).

On Linux or macOS, use `./mvnw` instead of `.\mvnw.cmd`.

## Local database

Default connection settings:

| Setting | Default |
| --- | --- |
| Host | `127.0.0.1` |
| Port | `5433` |
| Database | `durableflow` |
| Username | `durableflow` |
| Password | `durableflow_dev` (local development) |

Override these using `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, and `DB_PASSWORD`. Compose and Spring Boot use the same defaults. Set overrides in the PowerShell session before running Compose and the application, for example `$env:DB_PORT = '5434'`. Compose publishes only to localhost; `DB_HOST` configures the application's connection host.

The database uses a named Docker volume. `docker compose stop postgres`, `docker compose restart postgres`, and `docker compose down` preserve it. PostgreSQL initialization settings such as the username and password only create the database when the volume is first initialized; changing environment variables does not change existing database credentials.

Inspect the tables and migration history:

```powershell
docker compose exec postgres psql -U durableflow -d durableflow -c "\dt"
docker compose exec postgres psql -U durableflow -d durableflow -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Use your configured username/database if you override the defaults.

| Table | Purpose |
| --- | --- |
| `workflow_definitions` | Named, versioned workflow definitions stored as JSONB |
| `workflow_runs` | One execution of a definition, with input and status |
| `task_runs` | One logical task per workflow run, with its input/output and status |
| `task_attempts` | Separate execution attempts, including output and errors |
| `execution_events` | Status-change history linked to the correct run/task/attempt |

UUIDs identify records; timestamps use `TIMESTAMPTZ`. The application sets its default timezone to UTC before Spring Boot starts. The schema enforces valid states, positive attempt/version numbers, and unique task keys/attempt numbers. The API validates linear graphs and input references. Task claims and outcomes save state transitions and history in short transactions; handlers execute outside those transactions. V2 adds indexes for polling active work.

Flyway is the only schema initialization mechanism. Add new `V3__...sql`, `V4__...sql` migrations for future changes; do not edit a migration after it has been applied to a database you intend to keep. Flyway validates migration checksums during startup.

## Project layout

```text
pom.xml                                      Dependencies and build settings
compose.yaml                                 Local PostgreSQL and persistent volume
.mvn/wrapper/maven-wrapper.properties         Pinned Maven distribution
mvnw / mvnw.cmd                              Maven launchers
src/main/java/com/durableflow/
    DurableFlowApplication.java              Spring Boot entry point
    workflow/                                Models, validation, service, and JDBC repository
    api/                                     REST endpoints and Problem Details errors
    execution/                               Polling worker, handlers, claims, and outcomes
src/main/resources/application.yaml          Local server and health settings
src/main/resources/db/migration/              Versioned Flyway SQL migrations
src/test/java/com/durableflow/persistence/     PostgreSQL integration tests
src/test/java/com/durableflow/workflow/        Graph validation and transaction tests
src/test/java/com/durableflow/api/             HTTP API integration tests
src/test/java/com/durableflow/execution/       Handler, transaction, and background execution tests
examples/workflows/                          Example workflow JSON
examples/postman/                            Importable Postman collection
docs/API.md                                 API contract and walkthrough
docs/EXECUTION.md                           Execution flow and success/failure demos
```

Import `pom.xml` into your Java IDE and use `DurableFlowApplication` as the run configuration. General DAG dependencies and parallel execution are the next milestones.

Each feature uses a descriptive branch such as `feat/project-setup`, with a handful of focused commits. Development and verification run locally.

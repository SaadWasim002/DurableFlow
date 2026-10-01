# DurableFlow

A workflow execution engine built incrementally with Java and Spring Boot.

DurableFlow supports creating validated linear workflows, creating queued runs, and reading their tasks and attempts through REST APIs. Definitions and execution state are stored in local PostgreSQL. Sequential task execution is the next feature.

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
| `GET /tasks/{id}/attempts` | Read attempts; currently empty until execution arrives | 200 |

Follow the [complete API walkthrough](docs/API.md) for PowerShell requests, response fields, validation rules, and errors. A ready-to-submit workflow lives in [three-task-chain.json](examples/workflows/three-task-chain.json). Accepted runs remain `QUEUED` in this feature; the executor arrives next.

Import the [Postman collection](examples/postman/DurableFlow.postman_collection.json) to try every endpoint with automatically saved IDs. The walkthrough also includes [curl commands](docs/API.md#curl-commands-powershell).

## Build and run the JAR

```powershell
.\mvnw.cmd verify
java -jar target/durableflow-0.0.1-SNAPSHOT.jar
```

`verify` runs unit and integration tests and packages the application. Integration tests use Testcontainers to create isolated PostgreSQL 17 containers with random host ports, apply the real migrations, and remove the test containers afterward. Docker must be running; the Compose development database does not need to be running for tests. Test data is kept separate from your development database.

The 46 tests cover graph/reference validation, UTC connections, migrations, saved results, uniqueness, HTTP responses, populated attempt history, concurrent duplicate requests, and rollback of a run/tasks/history after a late insert failure. Starting the JAR requires the Compose database to be running.

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

UUIDs identify records; timestamps use `TIMESTAMPTZ`. The application sets its default timezone to UTC before Spring Boot starts, so database connections and default time calculations are independent of the machine's timezone. The schema enforces valid initial states, positive attempt/version numbers, and unique task keys/attempt numbers. The API validates linear graphs and input references. Execution state transitions arrive with the executor.

Flyway is the only schema initialization mechanism. Add new `V2__...sql`, `V3__...sql` migrations for future changes; do not edit a migration after it has been applied to a database you intend to keep. Flyway validates migration checksums during startup.

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
src/main/resources/application.yaml          Local server and health settings
src/main/resources/db/migration/              Versioned Flyway SQL migrations
src/test/java/com/durableflow/persistence/     PostgreSQL integration tests
src/test/java/com/durableflow/workflow/        Graph validation and transaction tests
src/test/java/com/durableflow/api/             HTTP API integration tests
examples/workflows/                          Example workflow JSON
docs/API.md                                 API contract and walkthrough
```

Import `pom.xml` into your Java IDE and use `DurableFlowApplication` as the run configuration. Scheduling and execution will be introduced in subsequent features.

Each feature uses a descriptive branch such as `feat/project-setup`, with a handful of focused commits. Development and verification run locally.

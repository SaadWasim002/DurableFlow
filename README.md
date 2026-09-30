# DurableFlow

A workflow execution engine built incrementally with Java and Spring Boot.

The project setup feature is complete: a runnable HTTP application with a health endpoint. The next feature adds local PostgreSQL and persistent workflow state.

## Requirements

- JDK 21 or a compatible newer JDK. This setup was verified with JDK 24.0.2.
- Network access for the first build to download Maven and dependencies.

Spring Boot is pinned to 4.1.1. The Maven Wrapper is pinned to Maven 3.9.12, so a separate Maven installation is optional. Java compilation targets release 21. See [Spring Boot's system requirements](https://docs.spring.io/spring-boot/system-requirements.html) for runtime compatibility.

## Run locally

From the project directory in PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

The server listens on `127.0.0.1:8080`. In a second terminal:

```powershell
curl.exe http://127.0.0.1:8080/actuator/health
```

Expect HTTP 200 and a JSON response containing `"status":"UP"`. Spring Boot may also include health group names. Stop the server with Ctrl+C; it allows up to 20 seconds for graceful shutdown.

If port 8080 is occupied, choose another port:

```powershell
$env:PORT = '8081'
.\mvnw.cmd spring-boot:run
```

Use the selected port in the health URL. Remove the override later with `Remove-Item Env:PORT`.

## Build and run the JAR

```powershell
.\mvnw.cmd verify
java -jar target/durableflow-0.0.1-SNAPSHOT.jar
```

`verify` compiles and packages the application and will run tests as they are added. This setup feature was checked by building through the wrapper, starting the packaged JAR, checking the health response and localhost binding, and confirming graceful shutdown. There are no automated tests yet.

On Linux or macOS, use `./mvnw` instead of `.\mvnw.cmd`.

## Project layout

```text
pom.xml                                      Dependencies and build settings
.mvn/wrapper/maven-wrapper.properties         Pinned Maven distribution
mvnw / mvnw.cmd                              Maven launchers
src/main/java/com/durableflow/
    DurableFlowApplication.java              Spring Boot entry point
src/main/resources/application.yaml          Local server and health settings
```

Import `pom.xml` into your Java IDE and use `DurableFlowApplication` as the run configuration. Workflow APIs, scheduling, and execution will be introduced in subsequent features.

Each feature uses a descriptive branch such as `feat/project-setup`, with a handful of focused commits. Development and verification run locally.

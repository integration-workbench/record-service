# Record Service

The second Java application in Integration Workbench. It will own local record storage and the API for managing those records, starting with Contacts.

The integration application owns Salesforce/HubSpot connections and CRM operations. Record Service owns local records. The initial KAN-29 milestone provides the independently runnable application; persistence and Contact endpoints follow in KAN-7 and KAN-30.

## Project location

This is a separate Spring Boot project and Git repository named `record-service`, alongside the existing integration project.

Run the commands below from the project root: the directory containing `pom.xml`, `mvnw` and `mvnw.cmd`.

Application entry point: `io.github.huck5.recordservice.RecordServiceApplication`.

## Requirements

- JDK 21, selected in IntelliJ or available through `JAVA_HOME` / `PATH`.
- Internet access for the first Maven dependency download.

The Maven wrapper is included, so a separate Maven installation is not required.

## Configuration

Set the application's name and port in `src/main/resources/application.properties`:

```properties
spring.application.name=record-service
server.port=8081
```

This gives Record Service the base address `http://localhost:8081`, while the integration application can continue using port 8080.

## Build

Linux/macOS:

```bash
./mvnw clean package
```

Windows PowerShell:

```powershell
.\mvnw.cmd clean package
```

This compiles the application, runs its configured tests and creates the packaged output under `target/`.

## Run

Linux/macOS:

```bash
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

Alternatively, open the project in IntelliJ and run `RecordServiceApplication`.

Check the startup output to confirm that the web server is listening on port 8081. Stop a terminal-launched application with `Ctrl+C`; stop an IDE-launched application with IntelliJ's Stop button.

## Test

Linux/macOS:

```bash
./mvnw test
```

Windows PowerShell:

```powershell
.\mvnw.cmd test
```

The generated `RecordServiceApplicationTests` class checks that the Spring application context starts. The Maven test command also runs additional tests as they are added.

## Run both applications locally

Open the integration project and Record Service in separate IntelliJ windows, then start each application's main class.

| Application | Local port |
| --- | --- |
| Integration application | 8080 |
| Record Service | 8081 |

Each application runs as its own Java process and can be built, started and stopped independently. Docker/Compose orchestration is a later milestone.

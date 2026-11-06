# Record Service

Record Service is the Spring Boot API for storing and managing local contacts in Integration Workbench. The separate CRM Integration Service handles Salesforce and HubSpot connections. Record Service runs independently on port 8081.

## Local setup

Install JDK 21 and start Docker Desktop, or run a Docker Engine with the Compose plugin. The project includes the Maven Wrapper.

From the directory containing `pom.xml`, start MySQL and check its logs for `ready for connections` on port 3306.

```bash
docker compose up -d mysql
docker compose logs --tail 30 mysql
```

Compose publishes MySQL at `127.0.0.1:3306` and stores its data in the `mysql-data` volume. The credentials below are the local development defaults from [compose.yaml](compose.yaml).

**Linux/macOS**

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/record_service'
export DB_USERNAME='record_app'
export DB_PASSWORD='local-record-password'
./mvnw spring-boot:run
```

**Windows PowerShell**

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/record_service'
$env:DB_USERNAME = 'record_app'
$env:DB_PASSWORD = 'local-record-password'
.\mvnw.cmd spring-boot:run
```

To run from IntelliJ, set the same three variables in the `RecordServiceApplication` run configuration. Flyway applies the database migrations on startup. The API is available at `http://localhost:8081`.

Stop the app with Ctrl+C or IntelliJ's Stop button. Run `docker compose stop mysql` to stop the local database and keep its data for the next run.

## Contact API

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/contacts` | Create a contact |
| GET | `/contacts` | List contacts |
| GET | `/contacts/{id}` | Read one contact |
| PUT | `/contacts/{id}?expectedVersion={version}` | Replace a contact's editable fields |
| DELETE | `/contacts/{id}?expectedVersion={version}` | Delete a contact |

Run these examples in a Bash shell, such as Git Bash on Windows.

### Create a contact

```bash
curl -i -X POST 'http://localhost:8081/contacts' \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Ada","lastName":"Lovelace","email":"ada@example.com"}'
```

The response includes the new contact's `id` and `version`.

### Update that contact

Replace `1` and `0` below with the `id` and `version` from the create response.

```bash
curl -i -X PUT 'http://localhost:8081/contacts/1?expectedVersion=0' \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Ada","lastName":"Lovelace","email":"ada.lovelace@example.com"}'
```

The response includes the updated contact and its new version. If you repeat the same request with the old version, the API returns `409 Conflict`. Read the contact again to get its current version before making another edit or deleting it.

## Tests and build

Run `./mvnw test` on Linux/macOS or `.\mvnw.cmd test` in PowerShell. The repository integration tests use Testcontainers to start their own MySQL instance. Docker must be running during those tests.

Use `./mvnw clean package` or `.\mvnw.cmd clean package` to run the tests and build the application under `target/`.

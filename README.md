# Record Service

Record Service is the Spring Boot API for storing and managing local contacts in [Integration Workbench](https://github.com/integration-workbench).

It provides versioned contact CRUD, local pagination, and explicit imports from Salesforce and HubSpot. The separate [CRM Integration Service](https://github.com/integration-workbench/crm-integration-service) handles provider authentication and CRM requests.

This project is a work in progress. Imports currently create previously unlinked contacts; ongoing synchronization and outbound CRM updates are planned.

## Local setup

Install JDK 21 and start Docker Desktop, or run Docker Engine with the Compose plugin. The project includes the Maven Wrapper.

From the directory containing `pom.xml`, start MySQL and check its logs for `ready for connections` on port 3306:

```bash
docker compose up -d mysql
docker compose logs --tail 30 mysql
```

Compose publishes MySQL at `127.0.0.1:3306` and stores its data in the `mysql-data` volume. The credentials below are the local development defaults from [compose.yaml](compose.yaml).

### Linux/macOS

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/record_service'
export DB_USERNAME='record_app'
export DB_PASSWORD='local-record-password'

./mvnw spring-boot:run
```

### Windows PowerShell

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/record_service'
$env:DB_USERNAME = 'record_app'
$env:DB_PASSWORD = 'local-record-password'

.\mvnw.cmd spring-boot:run
```

To run from IntelliJ, set the same three variables in the `RecordServiceApplication` run configuration.

Flyway applies database migrations on startup. The API is available at:

```text
http://localhost:8081
```

Stop the application with Ctrl+C or IntelliJ's Stop button. Stop MySQL while retaining its data with:

```bash
docker compose stop mysql
```

Ordinary local CRUD does not require CRM Integration Service to be running. CRM imports do.

## Contact API

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/contacts` | Create a contact |
| GET | `/contacts?page=0&size=25&sort=id,asc` | List a page of local contacts |
| GET | `/contacts/{id}` | Read one contact |
| PUT | `/contacts/{id}?expectedVersion={version}` | Replace a contact's editable fields |
| DELETE | `/contacts/{id}?expectedVersion={version}` | Delete a local contact |
| POST | `/contacts/imports?source={source}` | Import contacts from a configured CRM |

The following examples use Bash syntax. On Windows, they can be run in Git Bash.

### Create a contact

```bash
curl -i -X POST 'http://localhost:8081/contacts' \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Ada","lastName":"Lovelace","email":"ada@example.com"}'
```

The response includes the new contact's `id` and `version`.

Validation requires at least one of first name, last name, or email to contain text. First name supports up to 50 characters, last name up to 80, and email must satisfy the configured email-format constraint when supplied.

### List local contacts

```bash
curl -i 'http://localhost:8081/contacts?page=0&size=25&sort=id,asc'
```

The response contains:

- `contacts`: contacts on the requested page.
- `length`: total number of local contacts.
- `pageIndex`: zero-based page index.
- `pageSize`: requested page size.

Without pagination parameters, the API returns the first page of 25 contacts, sorted by ID.

This local database pagination is separate from pagination of CRM retrieval requests.

### Read one contact

```bash
curl -i 'http://localhost:8081/contacts/1'
```

### Update a contact

Replace `1` and `0` below with the contact's current `id` and `version`:

```bash
curl -i -X PUT 'http://localhost:8081/contacts/1?expectedVersion=0' \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Ada","lastName":"Lovelace","email":"ada.lovelace@example.com"}'
```

The response includes the updated contact and its new version.

An outdated expected version produces `409 Conflict`. Read the contact again to obtain its current version before making another edit or deleting it.

### Delete a local contact

Use the contact's current version:

```bash
curl -i -X DELETE \
  'http://localhost:8081/contacts/1?expectedVersion=1'
```

A successful deletion returns `204 No Content`. This operation does not delete contacts from Salesforce or HubSpot.

CRM-linked contacts also have foreign-key references from their identity links. There is currently no automatic unlinking or cascade deletion for those contacts.

## Import contacts

### Prerequisites

1. Start Record Service and its MySQL database.
2. Configure and start [CRM Integration Service](https://github.com/integration-workbench/crm-integration-service) on `http://localhost:8080`, following that repository's setup instructions.
3. Ensure the local `crm_connections` entries describe the CRM accounts configured in CRM Integration Service.

The bundled seed migration contains the project's demonstration account entries. Before importing from your own accounts, adjust the local connection data to use the corresponding Salesforce organization ID or HubSpot account ID.

The initial implementation supports one configured account per provider. These connection entries identify the accounts; they do not store credentials or dynamically select different CRM credentials.

### Request an import

Salesforce:

```bash
curl -i -X POST \
  'http://localhost:8081/contacts/imports?source=SALESFORCE'
```

HubSpot:

```bash
curl -i -X POST \
  'http://localhost:8081/contacts/imports?source=HUBSPOT'
```

Use the uppercase source names shown above.

The request runs synchronously and returns a summary when processing finishes.

Example response:

```json
{
  "source": "SALESFORCE",
  "fetched": 251,
  "imported": 251,
  "skipped": 0,
  "invalidExternalId": 0,
  "invalidContact": 0,
  "failed": 0
}
```

| Field | Meaning |
| --- | --- |
| `source` | Requested CRM provider |
| `fetched` | Number of contacts returned by the retrieval call |
| `imported` | New local contacts whose contact-and-link transaction committed |
| `skipped` | Contacts already linked, including repeated external IDs after a successful import within the same response |
| `invalidExternalId` | Contacts with a null, empty, or whitespace-only external ID |
| `invalidContact` | Contacts rejected by local contact validation |
| `failed` | Contact/link persistence attempts rejected by a database integrity constraint |

For a completed response, each fetched contact has one outcome:

```text
fetched = imported + skipped + invalidExternalId + invalidContact + failed
```

Imported contacts can then be retrieved through the ordinary local Contact API.

### Identity and repeat imports

Record Service stores CRM identity links using the selected connection and the CRM contact's external ID.

- A new source identity creates a local contact and its link in one transaction.
- An already-linked identity is skipped, preserving the current local contact fields.
- Names and email addresses are not used to infer identity or merge contacts.
- Contacts from different CRM connections remain separate unless explicitly linked through future functionality.
- Importing does not request outbound writes to either CRM.

Repeating an unchanged successful import should therefore report zero newly imported contacts and count the existing identities as skipped.

### Failure behaviour

Contact validation failures and database integrity failures are counted individually, and processing continues with subsequent contacts.

Other failures, such as an unsuccessful retrieval request, malformed JSON, or an unexpected database/resource failure, abort the operation. Contacts committed before an abort remain saved; the entire import is not one transaction. An aborted request does not return the normal completed summary.

### Current retrieval limits

Each import processes the contacts returned by one CRM Integration Service retrieval call. Additional provider pages are not followed yet, so `fetched` does not necessarily represent every contact in the CRM.

The current summary does not expose provider continuation information or whether additional pages exist. Explicit coverage metadata, full pagination, import history and checkpointed recovery are follow-up work.

Changes to already-linked CRM contacts are not applied by repeat imports. Ongoing change detection, conflict handling and bidirectional synchronization are separate planned capabilities.

## Tests and build

Run the full test suite:

**Linux/macOS**

```bash
./mvnw test
```

**Windows PowerShell**

```powershell
.\mvnw.cmd test
```

Service unit tests use controlled dependencies and do not require live CRM credentials or running CRM services. Repository integration tests use Testcontainers to start their own MySQL instance, so Docker must be running for the full suite.

Build the application and run its tests with:

```bash
./mvnw clean package
```

Or in PowerShell:

```powershell
.\mvnw.cmd clean package
```

The packaged application is written under `target/`.
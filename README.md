# Record Service

Record Service is the Spring Boot API for storing and managing local contacts in [Integration Workbench](https://github.com/integration-workbench).

It provides versioned contact CRUD, local pagination, and explicit imports from Salesforce and HubSpot. The separate [CRM Integration Service](https://github.com/integration-workbench/crm-integration-service) handles provider authentication and CRM requests.

Imports currently create previously unlinked contacts. Ongoing synchronization and outbound CRM updates are planned.

## Local setup

Install JDK 21 and start Docker Desktop, or run Docker Engine with the Compose plugin. The Maven Wrapper is included.

From the repository root, start MySQL:

```bash
docker compose up -d mysql
docker compose logs --tail 30 mysql
```

Wait for `ready for connections` on port 3306.

Compose publishes MySQL at `127.0.0.1:3306` and stores its data in the `mysql-data` volume. The credentials below are local development defaults from [compose.yaml](compose.yaml).

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

Flyway applies database migrations on startup. The API is available at `http://localhost:8081`.

Stop the application with Ctrl+C or IntelliJ's Stop button. Stop MySQL while retaining its data with:

```bash
docker compose stop mysql
```

Ordinary local CRUD does not require CRM Integration Service to be running. CRM imports do.

## Contact API

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/contacts` | Create a contact |
| GET | `/contacts?page=0&size=25&sort=id,asc` | List local contacts |
| GET | `/contacts/{id}` | Read one contact |
| PUT | `/contacts/{id}?expectedVersion={version}` | Replace editable contact fields |
| DELETE | `/contacts/{id}?expectedVersion={version}` | Delete a local contact |
| POST | `/contacts/imports?source={source}` | Import a page of CRM contacts |

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

| Field | Meaning |
| --- | --- |
| `contacts` | Contacts on the requested page |
| `length` | Total number of local contacts |
| `pageIndex` | Zero-based page index |
| `pageSize` | Requested page size |

Without pagination parameters, the API returns the first page of 25 contacts, sorted by ID.

Local database pagination is separate from CRM retrieval pagination.

### Read one contact

```bash
curl -i 'http://localhost:8081/contacts/1'
```

### Update a contact

Replace the ID and expected version with the contact's current values:

```bash
curl -i -X PUT 'http://localhost:8081/contacts/1?expectedVersion=0' \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Ada","lastName":"Lovelace","email":"ada.lovelace@example.com"}'
```

The response includes the updated contact and its new version.

An outdated expected version produces `409 Conflict`. Read the contact again to obtain its current version before updating or deleting it.

### Delete a local contact

```bash
curl -i -X DELETE \
  'http://localhost:8081/contacts/1?expectedVersion=1'
```

A successful deletion returns `204 No Content`. This does not delete the contact from Salesforce or HubSpot.

CRM-linked contacts have foreign-key references from their identity links. There is currently no automatic unlinking or cascade deletion for those contacts.

## Import contacts

### Prerequisites

1. Start Record Service and its MySQL database.
2. Configure and start [CRM Integration Service](https://github.com/integration-workbench/crm-integration-service) on `http://localhost:8080`.
3. Ensure the local `crm_connections` entries describe the accounts configured in CRM Integration Service.

The bundled seed migration contains demonstration account entries. Before importing from your own accounts, adjust the local connection data to use the corresponding Salesforce organization ID or HubSpot account ID.

The initial implementation supports one configured account per provider. Connection entries identify those accounts; they do not store credentials or dynamically select different CRM credentials.

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

Use the uppercase source names shown above. No JSON request body is required.

A missing or unsupported source produces `400 Bad Request`.

The request runs synchronously and returns a `ContactImportResponse` after processing the retrieved page.

### Import response

Example Salesforce repeat import:

```json
{
  "source": "SALESFORCE",
  "fetched": 200,
  "imported": 0,
  "skipped": 200,
  "invalidExternalId": 0,
  "invalidContact": 0,
  "failed": 0,
  "totalAvailable": 251,
  "hasMore": true,
  "continuationToken": "/services/data/v66.0/query/example-200"
}
```

Example HubSpot first import:

```json
{
  "source": "HUBSPOT",
  "fetched": 10,
  "imported": 10,
  "skipped": 0,
  "invalidExternalId": 0,
  "invalidContact": 0,
  "failed": 0,
  "totalAvailable": null,
  "hasMore": true,
  "continuationToken": "288431635415"
}
```

Continuation values are examples; actual values come from the provider response.

| Field | Meaning |
| --- | --- |
| `source` | Requested CRM provider |
| `fetched` | Number of contacts returned in the retrieved page |
| `imported` | New local contacts whose contact-and-link transaction committed |
| `skipped` | Already-linked identities, including repeated external IDs after a successful import within the response |
| `invalidExternalId` | Contacts with a null, empty, or whitespace-only external ID |
| `invalidContact` | Contacts rejected by local contact validation |
| `failed` | Contact/link persistence attempts rejected by a database integrity constraint |
| `totalAvailable` | Total matching contacts reported by the provider, or `null` when unavailable |
| `hasMore` | Whether the provider response indicates another page |
| `continuationToken` | Provider continuation value, or `null` when no further page is indicated |

For a completed response, each fetched contact has one outcome:

```text
fetched = imported + skipped + invalidExternalId + invalidContact + failed
```

The retrieval metadata is preserved independently of the import outcomes. Skipping every contact on a page does not change whether the provider has another page.

Salesforce supplies `totalAvailable`; HubSpot's contacts-list response does not, so HubSpot returns `null`. A null total means unknown, not zero.

Imported contacts are available through the ordinary local Contact API.

### Identity and repeat imports

Record Service identifies CRM contacts using the selected connection and the external contact ID.

- A new source identity creates a local contact and its link in one transaction.
- An already-linked identity is skipped, preserving current local contact fields.
- Names and email addresses are not used to infer identity or merge contacts.
- Contacts from different CRM connections remain separate unless explicitly linked through future functionality.
- Importing does not write changes to either CRM.

Repeating an unchanged successful import should report zero new imports and count existing identities as skipped.

### Empty results

An empty successful retrieval returns a normal summary with all import counts set to zero. No contacts or identity links are created.

Provider metadata is retained. A completed empty Salesforce result can report `totalAvailable: 0`; HubSpot retains `totalAvailable: null`.

### Failure behaviour

Contact validation failures and database integrity failures are counted individually, and processing continues with subsequent contacts.

The contact and its CRM identity link are saved atomically. If link persistence fails, the new contact is rolled back rather than left without its link.

Other failures, such as an unsuccessful retrieval request, malformed JSON, or an unexpected database/resource failure, abort the operation.

Each contact has its own transaction. Contacts committed before an abort remain saved, and an aborted request does not return the normal completed summary.

### Current retrieval limits

Each import makes one CRM Integration Service retrieval call and processes the returned page.

The current connector configuration requests:

| Provider | Requested page size |
| --- | --- |
| Salesforce | Batch size of 200 |
| HubSpot | Limit of 10 |

These are retrieval settings in CRM Integration Service, not caller-selectable parameters on the import endpoint.

`fetched` describes the current page, while `totalAvailable` describes the provider-reported total when available.

The response exposes additional-page metadata, but Record Service does not yet follow or accept continuation tokens. Repeating the same import request starts another initial-page retrieval; it does not advance to the next page.

Full pagination, import history, and checkpointed recovery are separate work tracked under KAN-51.

Repeat imports do not update already-linked contacts. Ongoing change detection, conflict handling, and bidirectional synchronization remain separate capabilities.

## Tests and build

Run the full suite:

### Linux/macOS

```bash
./mvnw test
```

### Windows PowerShell

```powershell
.\mvnw.cmd test
```

Service unit tests use controlled dependencies without live CRM credentials or running CRM services. Database integration tests use Testcontainers to start their own MySQL instance, so Docker must be running for the full suite.

Relevant test areas include:

- Contact CRUD, validation, and optimistic locking.
- Import counts and existing-identity handling.
- Retrieval metadata preserved for new, existing, and empty results.
- HTTP serialization of import summaries.
- Missing and unsupported import sources.
- Transaction rollback when identity-link persistence fails.

Build the application and run its tests:

```bash
./mvnw clean package
```

Or in PowerShell:

```powershell
.\mvnw.cmd clean package
```

The packaged application is written under `target/`.
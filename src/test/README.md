# Test scope

The import tests follow the current component boundaries:

| Test | Responsibility |
| --- | --- |
| `ContactImportClientTest` | One HTTP request, source routing, cursor encoding, envelope parsing, and preserved network failures. |
| `ContactImportTaskletTest` | Page traversal, outcome totals, sequential writes, one-page prefetch, and propagated fatal failures. |
| `ContactImportJobServiceTest` | Job parameters, completed summary, and incomplete executions with preserved causes. |
| `ContactControllerTest` | HTTP binding, summary JSON, and nested import failures mapped to Problem Details. |
| `ContactImportPersistenceServiceIntegrationTest` | Real MySQL contact/link atomicity, per-contact commits, invalid records, and duplicate handling that preserves local edits. |
| `ContactImportJobIntegrationTest` | Real Batch job/step metadata, multipage persistence, retrieval failure, and restart accounting. |
| `CrmContactLinkRepositoryTest` | Connection-scoped lookup and database uniqueness constraints. |

Existing CRUD validation, optimistic locking, and overlapping update tests remain.
No separate tests are needed for DTO builders, the enum, exception constructors, or executor getters.
The real job tests exercise `BatchConfig`; they do not replace the tasklet with a test implementation.

## Run

Java 21; Docker is required for the MySQL integration and repository tests.

```bash
# Fast component and MVC tests; no Docker or running CRM services required.
./mvnw -Dtest=ContactImportClientTest,ContactImportTaskletTest,ContactImportJobServiceTest,ContactControllerTest,ContactCrudServiceTest test

# Complete suite, including disposable MySQL containers.
./mvnw test
```

The prefetch test uses explicit latches with bounded waits, not sleeps. It blocks each
current page's persistence, observes the next page's retrieval, and verifies that
neither the following fetch nor the next page's persistence has started prematurely.

## Outstanding recovery requirement

`restartAfterPageTwoFailurePreservesImportedTotalsAndLocalEdits` intentionally specifies
the desired recovery behavior. It is expected to fail against the current tasklet:
that tasklet stores totals only at the end and replays page one as `SKIPPED` after a
failure. The test requires totals for the same logical import to retain that earlier
record as `IMPORTED`, without creating duplicates or overwriting local edits.
This is a behavior gap to implement, not a reason to weaken or disable the test.

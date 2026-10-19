# AI-Powered Accounting & Financial Management System - Backend

Production-oriented SaaS backend for AI-powered accounting and financial management. It includes organizations, users and memberships, JWT authentication, vendors, customers, chart of accounts, journal entries, expenses, invoices, document uploads, OCR, AI extraction, human review, asynchronous Kafka processing, optional Redis caching, financial reports, an AI assistant, and an append-only audit trail.

## Purpose

Provide a reproducible, production-oriented backend with MySQL 8.x and Flyway, tenant isolation and RBAC, correctness-first accounting invariants, asynchronous document processing, and a local Docker environment for development and operations verification.

## Technology Stack

- **Java** 21
- **Spring Boot** 3.3.6
- **Spring Web** (REST)
- **Spring Data JPA / Hibernate 6**
- **MySQL 8.x** (production/development)
- **Flyway** (core + MySQL) for database migrations
- **H2** (test profile, in-memory, `MODE=MySQL`)
- **Maven** 3.9+
- **Bean Validation** (Jakarta Validation)
- **MySQL Connector/J** (`com.mysql:mysql-connector-j`)
- **Spring Security + JWT** (jjwt 0.12.6)
- **Spring Kafka** (asynchronous OCR/extraction workers)
- **Spring Data Redis + Spring Cache** (optional performance layer)
- **Spring AI 1.0.0** (OpenAI-compatible, env-driven)
- **Spring Boot Actuator** (liveness/readiness probes only)
- **Docker / Docker Compose** (local development stack)
- **JUnit 5 + Mockito + MockMvc + AssertJ**
- **Lombok removed** — no code uses it; prevents `TypeTag :: UNKNOWN` on JDK 26. Can be re-added with `1.18.34+` if needed

## Requirements

- Java 21 (Microsoft OpenJDK 21 or Temurin 21 recommended)
- Maven 3.9+ (`mvn -v` should show Java 21)
- MySQL 8.x running locally for `dev` profile, or Docker Compose for the local stack
- Docker Engine 24+ and Docker Compose v2 (optional; not required for unit tests)

Verify:

```bash
java -version
./mvnw -version
mysql --version
```

## Project Structure

```
com.example.accounting
├── AccountingBackendApplication.java
├── ai/             # Spring AI extraction + deterministic fallback
├── assistant/      # read-only AI financial assistant
├── audit/          # append-only audit trail
├── cache/          # optional Redis cache + in-memory fallback
├── config/         # security, JWT, CORS, JPA auditing
├── controller/     # REST controllers (health, auth, accounting, documents, ...)
├── dto/            # request/response DTOs
├── entity/         # JPA entities
├── exception/      # domain exceptions + global error handling
├── kafka/          # asynchronous OCR/extraction events, DLQ, workers
├── ocr/            # OCR provider abstraction (Tesseract + fake)
├── report/         # financial and operational reports
├── repository/     # Spring Data JPA repositories
├── security/       # JWT service, refresh tokens, tenant access checks
├── service/        # business logic
└── storage/        # local document storage abstraction

src/main/resources
├── application.properties        # common config (port, JPA validate, probes, cache, Kafka)
├── application-dev.properties    # local MySQL via env vars, Flyway enabled
├── application-prod.properties   # production MySQL/Flyway/JWT/CORS hardening
├── application-test.properties   # H2 in-memory, no MySQL/Kafka/Redis required
└── db/migration/                 # Flyway V1–V16 (immutable once applied)
```

Package root `com.example.accounting` can be renamed later via IDE refactor without structural changes.

## MySQL Configuration

### Environment Variables (dev/prod)

All sensitive values are externalized. No hard-coded credentials or secrets.

| Variable | Description | Default (dev) |
|---|---|---|
| `DB_HOST` | MySQL host | `localhost` |
| `DB_PORT` | MySQL port | `3306` |
| `DB_NAME` | Database name | `accounting_db` |
| `DB_USERNAME` | DB user | `root` |
| `DB_PASSWORD` | DB password | *(empty)* |
| `SERVER_PORT` | HTTP port | `8080` |
| `SPRING_PROFILES_ACTIVE` | Active profile | `dev` |

JDBC URL (from `application-dev.yml:3`):

```
jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:accounting_db}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
```

Username/password are injected via `${DB_USERNAME:root}` and `${DB_PASSWORD:}`. Never commit secrets.

### Profiles and JPA

- `application.properties` — `spring.jpa.hibernate.ddl-auto=validate` (safe default; Flyway manages schema)
- `application-dev.properties` — also `validate` + `MySQLDialect` + Flyway `enabled=true` (dev connects to real MySQL)
- `application-prod.properties` — `validate` + Flyway `enabled=true`, `clean-disabled=true`, `validate-on-migrate=true`
- `application-test.properties` — H2 with `MODE=MySQL`, `ddl-auto=create-drop`, Flyway `enabled=false` — no external DB needed for tests

Production **does not** auto-create or modify schemas. All schema changes go through Flyway migrations in `db/migration`.

### Flyway

- Location: `classpath:db/migration`
- Baseline: `V1__baseline.sql` — placeholder with `SELECT 1;` to establish history
- `baseline-on-migrate: true` set in base, dev, and prod profiles
- Migrations `V2`–`V16` create the business schema. Never edit an applied migration; add a new version (for example `V17__...`) for any future schema change. Never use `ddl-auto: update/create` in production.

## MySQL 8.x Setup

### 1. Install MySQL 8.x

macOS (Homebrew):

```bash
brew install mysql@8.4
brew services start mysql@8.4
# or
brew install mysql
brew services start mysql
```

Linux (apt):

```bash
sudo apt update && sudo apt install mysql-server
sudo service mysql start
```

Docker alternative:

```bash
docker run --name accounting-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=accounting_db -p 3306:3306 -d mysql:8.4
```

### 2. Create Database and User

```bash
mysql -h localhost -P 3306 -u root -p
```

Inside `mysql`:

```sql
CREATE DATABASE accounting_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'accounting'@'localhost' IDENTIFIED BY 'strong_password';
GRANT ALL PRIVILEGES ON accounting_db.* TO 'accounting'@'localhost';
FLUSH PRIVILEGES;
EXIT;
```

Or using root with no password (local dev only):

```bash
mysql -u root -e "CREATE DATABASE IF NOT EXISTS accounting_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

Verify connection:

```bash
mysql -h localhost -P 3306 -u root -e "SELECT 1;"
mysql -h localhost -P 3306 -u root -D accounting_db -e "SHOW TABLES;"
```

### 3. Environment Variables

Export for current shell:

```bash
export DB_HOST=localhost
export DB_PORT=3306
export DB_NAME=accounting_db
export DB_USERNAME=root
export DB_PASSWORD=root   # or empty if root has no password
```

Persist via `.zshrc` / `.bashrc` or use `.env` with direnv. Do not commit `.env`.

## How to Run the Application

### Navigate

```bash
cd accounting-backend
```

### Development (MySQL required)

```bash
# defaults: localhost:3306/accounting_db, root/<empty>
./mvnw spring-boot:run

# explicitly
SPRING_PROFILES_ACTIVE=dev DB_HOST=localhost DB_PORT=3306 DB_NAME=accounting_db DB_USERNAME=root DB_PASSWORD=root ./mvnw spring-boot:run

# jar
./mvnw clean package -DskipTests
java -jar target/accounting-backend-0.0.1-SNAPSHOT.jar

# with environment example for custom user
DB_HOST=127.0.0.1 DB_NAME=accounting_db DB_USERNAME=accounting DB_PASSWORD=secret java -jar target/accounting-backend-0.0.1-SNAPSHOT.jar
```

Application starts on `http://localhost:8080`.

Check health:

```bash
curl http://localhost:8080/api/v1/health
# {"status":"UP","service":"accounting-backend"}
```

### Test Profile (no MySQL needed)

Tests automatically use `test` profile + H2. No DB setup required.

## How to Run Tests

```bash
./mvnw test
# verbose
./mvnw test -Dtest=HealthControllerTest
./mvnw test -Dtest=ApplicationConfigurationTest
```

The suite (currently **565 tests**) covers accounting correctness, tenant isolation,
RBAC/authentication, Kafka processing/idempotency, Redis fallback, documents/OCR/AI
review, reports, the AI assistant, audit logging, and the V21 configuration/security
tests. No Docker, MySQL, Kafka, Redis, Tesseract, or external LLM is required.

New V21 tests:

- `JwtSecretValidatorTest` — production rejects missing/blank/short/known-default/placeholder secrets.
- `ProductionConfigurationTest` — checked-in `prod` profile uses MySQL, Flyway validate mode, strict JWT, health-only Actuator, safe CORS defaults, and graceful shutdown.
- `ActuatorSecurityTest` — health contract unchanged; liveness/readiness public; details hidden; other Actuator endpoints and business APIs remain protected.
- `CorsConfigTest`, `CorsDisabledByDefaultTest`, `CorsAllowlistTest` — empty CORS by default, wildcard rejection, explicit allowlist behavior.

## Health, Readiness, and Liveness

The business health contract is unchanged and public:

```
GET /api/v1/health
```

Response `200 OK`:

```json
{
  "status": "UP",
  "service": "accounting-backend"
}
```

Implemented via `controller/HealthController.java:11` with `/api/v1` base path (API versioning ready).

Actuator adds container-orchestration probes. Only `health` is exposed; all other
Actuator endpoints are not exposed and require authentication if mapped.

| Endpoint | Purpose | Public | Notes |
|---|---|---|---|
| `GET /api/v1/health` | Business health contract | Yes | Stable response above; must not change |
| `GET /actuator/health` | Overall health | Yes | Top-level status only; details hidden |
| `GET /actuator/health/liveness` | Liveness probe | Yes | `livenessState` only; does not depend on MySQL |
| `GET /actuator/health/readiness` | Readiness probe | Yes | `readinessState`, DB, and disk space |
| `GET /actuator/info` | Not exposed | — | Removed from the exposure list |
| `/actuator/env`, `/actuator/loggers`, ... | Not exposed | — | Not available in production |

Health responses use `management.endpoint.health.show-details=never`, so they do
not reveal database URLs, credentials, environment variables, or stack traces.
Redis health is disabled because Redis is optional; Kafka outages do not fail
readiness (workers retry in the background and the HTTP API remains available).
Liveness deliberately excludes external dependencies to avoid restart loops.

## Error Handling

Global handler: `exception/GlobalExceptionHandler.java:21` annotated with `@RestControllerAdvice`.

Handles:

- `MethodArgumentNotValidException` → `400` with `fieldErrors` map
- `ConstraintViolationException` → `400`
- `MethodArgumentTypeMismatchException` → `400`
- `HttpMessageNotReadableException` → `400`
- `NoHandlerFoundException` → `404`
- `IllegalArgumentException` → `400`
- `Exception` (fallback) → `500` with generic message + logged stacktrace

Response shape (`dto/ApiErrorResponse.java:9`):

```json
{
  "timestamp": "2026-09-20T12:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/v1/...",
  "fieldErrors": {
    "email": "must not be blank"
  }
}
```

Domain exceptions map to safe HTTP responses through the same global handler; accounting invariants and tenant isolation remain enforced in the services.

## Code Quality Notes

- Constructor injection only (no field injection)
- Small, focused classes, SOLID where applicable
- No interfaces for every class (YAGNI)
- No overengineering / speculative abstractions
- Lombok removed (foundation has no Lombok usage; avoids `TypeTag` crash on JDK 26)
- Bean Validation ready (`spring-boot-starter-validation:37`)
- `open-in-view: false` to avoid N+1/session leaks
- `maven-compiler-plugin` pinned to `release 21` + `parameters:true`
- MySQL `serverTimezone=UTC` and `allowPublicKeyRetrieval=true` for dev convenience
- Migrations separated from entity mapping; entities will be validated against Flyway schema

## Known Limitations / Out of Scope

The planned backend roadmap is implemented, but the following remain intentionally out of scope:

- Payments/reconciliation, bank integrations, and FX conversion.
- Transactional outbox / exactly-once event delivery (existing Kafka retry + DLQ + idempotent worker behavior is preserved).
- Cloud object storage (local document storage only) and cloud/Kubernetes deployment manifests.
- Automatic audit-log retention/purge and automated backup scheduling.
- A CI/CD platform (the Dockerfile/Compose stack is local-development tooling).

## Troubleshooting

- `Cannot load driver class: com.mysql.cj.jdbc.Driver` → ensure `com.mysql:mysql-connector-j:41` is on classpath (`./mvnw dependency:tree`).
- `Communications link failure` / `Connection refused` → ensure MySQL is running (`brew services list | grep mysql` or `docker ps`), and `DB_*` env vars are correct.
- `Validate failed: Table ... doesn't exist` → expected with `validate`; run Flyway migrations (`V1__baseline.sql` already applied) and ensure business entities are not created before their migrations.
- `FlywayException: Validate failed` → check `src/main/resources/db/migration` for checksum drift; do not manually edit applied migrations.
- `java version 21 required` / `ExceptionInInitializerError: TypeTag :: UNKNOWN` → project is Java 21; your system defaults to JDK 26. Fix:
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)
  export PATH=$JAVA_HOME/bin:$PATH
  java -version  # must show 21.0.x
  ```
  IntelliJ: File → Project Structure → SDK = 21, Preferences → Build Tools → Maven → Runner JRE = 21, then `File → Invalidate Caches / Restart`. Lombok removed to avoid JDK 26 incompatibility; if you re-add Lombok use `1.18.34+`.
- `Cannot load driver class: org.h2.Driver` when running `java -jar` with `test` → H2 is `runtime` scoped so it is on the classpath; run with `./mvnw` or `java -jar --spring.profiles.active=test`. For `dev` you still need MySQL.
- `Mockito cannot mock this class` / `Could not modify all classes` → run the suite with Java 21 (`JAVA_HOME`), not JDK 26; Mockito's inline mock maker is not compatible with this project's JDK 26 setup.
- `mvn not found` → use `./mvnw` wrapper (already included).

## Document Upload & OCR

**Document Upload**
- `POST /api/v1/documents` `multipart/form-data` `file, documentType, invoiceId?, expenseId?` → `201` `DocumentResponse` (no `storageKey` exposed)
- Supported `Content-Type`: `application/pdf`, `image/jpeg`, `image/png`, `image/webp` (extension fallback); others → `400`
- Max size `10MB` default via `DOCUMENT_MAX_FILE_SIZE` (`spring.servlet.multipart.max-file-size`); oversized → `400` (not raw multipart exception)
- `DocumentType` enum `INVOICE,RECEIPT,EXPENSE_RECEIPT,OTHER` validated; `invoiceId+expenseId` together → `400`; cross-tenant invoice/expense → `400`
- Storage abstraction `DocumentStorageService` (`store/retrieve/delete/exists`) on `storageKey` (`organizations/{orgId}/documents/{docId}/{uuid.ext}`), `LocalDocumentStorageService` via `DOCUMENT_STORAGE_PATH` (default `./storage/documents`, test `target/test-storage`), `Path.resolve(normalize).startsWith(root)` traversal guard, `SHA-256` checksum 64 hex

**Document Lifecycle**
- `UPLOADED` on upload → `PROCESSING` on `POST /documents/{id}/ocr` → `PROCESSED` or `FAILED` (client cannot set status)
- `GET /api/v1/documents/{id}` `200/404`, `GET /{id}/download` `200` with `Content-Type/Length/Disposition` (sanitized filename), tenant `404`, `GET /api/v1/documents?documentType&status&invoiceId&expenseId&originalFileName&page&size` tenant `Page`, `DELETE /{id}` `204` (physical file + metadata, not invoice/expense)

**OCR**
- `POST /api/v1/documents/{documentId}/ocr` → `200` `OcrResultResponse` (synchronous, future Kafka-ready), `GET /api/v1/documents/{documentId}/ocr` → `200`
- `OcrResult` (`id, document FK UNIQUE, status PENDING→PROCESSING→COMPLETED/FAILED, extractedText LONGTEXT, pageCount, processingStarted/CompletedAt, errorMessage 2000, createdAt/updatedAt`) `V13`, `ocr_results` `idx_ocr_status/document_id`
- `OcrStatus` `PENDING,PROCESSING,COMPLETED,FAILED`, `DocumentStatus` `UPLOADED→PROCESSING→PROCESSED/FAILED` integrated
- `TesseractOcrProvider` (`app.ocr.tesseract-command=${TESSERACT_COMMAND:tesseract}`, `app.ocr.tesseract-data-path=${TESSERACT_DATA_PATH:}`) + `FakeOcrProvider` (`@Profile test` `@Primary` deterministic: `corrupt` → `FAILED`, `multi-2pages` → `2`, otherwise `Fake OCR` text)
- PDF: `PDFBox Loader.loadPDF` + `PDFTextStripper` if text length >20 else fallback OCR (rendering not fully configured, returns placeholder with pageCount); Image: `tesseract` `Runtime.exec` with 30s timeout, fallback `[OCR fallback]` if not installed; `isAvailable()` checks `tesseract --version` gracefully, failure sanitized (no stack/path, `Failed to ...` 1000 chars, `[path]` redacted)
- Temp files `Files.createTempDirectory("ocr-")` + `createTempFile` + `deleteIfExists` in `finally` (parent cleanup)
- Authorization: `OWNER,ADMIN,ACCOUNTANT` can `POST /ocr`, `EMPLOYEE,VIEWER` → `403`, `EMPLOYEE/VIEWER` can `GET /ocr` → `200`, missing `401`, tenant isolation (`Org B` on `Org A` doc → `404`), `PROCESSING` second `POST` → `409`, `COMPLETED/FAILED` reprocess resets same row (no duplicate `document_id UNIQUE`)

**Example**
```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@invoice.pdf;type=application/pdf" \
  -F "documentType=INVOICE" -F "invoiceId=$INV_ID"
# → 201 {id, checksum, status:UPLOADED}
curl -X POST http://localhost:8080/api/v1/documents/$ID/ocr -H "Authorization: Bearer $TOKEN" # → 200 {status:COMPLETED, extractedText, pageCount}
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/documents/$ID/download -o out.pdf
```

Tesseract install (optional for real OCR, tests use `FakeOcrProvider`):
```bash
brew install tesseract # macOS
sudo apt install tesseract-ocr # Ubuntu
export TESSERACT_COMMAND=tesseract
export TESSERACT_DATA_PATH=/usr/share/tessdata
# if not installed, OCR returns fallback text and FAILED with sanitized error, not stack trace
```

**Configuration**
| Variable | Default | Description |
|---|---|---|
| `DOCUMENT_STORAGE_PATH` | `./storage/documents` | Local root, test `target/test-storage` |
| `DOCUMENT_MAX_FILE_SIZE` | `10MB` | `spring.servlet.multipart` both + service check |
| `TESSERACT_COMMAND` | `tesseract` | Executable for OCR |
| `TESSERACT_DATA_PATH` | `` | `TESSDATA_PREFIX` |
| `SPRING_AI_API_KEY` | `dummy` | OpenAI / provider API key (env only, never hard-coded) |
| `SPRING_AI_MODEL` | `gpt-4o-mini` | Chat model name |
| `SPRING_AI_PROVIDER` | `openai` | Provider hint (future: azure, anthropic, etc) |

Spring AI config (`application.properties:30-32`):
```
spring.ai.openai.api-key=${SPRING_AI_API_KEY:dummy}
spring.ai.openai.chat.options.model=${SPRING_AI_MODEL:gpt-4o-mini}
```
Dummy fallback keeps startup safe without network; real provider invoked only when key length >=10 and `ChatClient` present.

**Security Notes**
- Never trust `originalFileName` as path (UUID `storedFileName`), `PathTraversal` guard, validate `contentType` + extension, size, org, never expose `absolute path/storageKey/storedFileName` (only `originalFileName`), never log `JWT/refresh/password`, `401` missing, `403` insufficient role

## Spring AI Structured Document / Invoice Extraction (V14)

**Overview** — Advisory-only layer on top of OCR. Never creates `Invoice`, `Expense`, `JournalEntry`, `Vendor`, `Customer`, `Account` or modifies balances. Structured suggestion is persisted for future human review.

**Architecture**
```
Document (UPLOADED→PROCESSING→PROCESSED via OCR)
  ↓ extractedText (OcrResult COMPLETED)
DocumentExtractionController
  ↓ POST /documents/{id}/extraction (OWNER/ADMIN/ACCOUNTANT) | GET (all roles)
DocumentExtractionService (tenant-isolated, validates OCR COMPLETED, guards 409 while PROCESSING)
  ↓ AiDocumentExtractor (provider-neutral interface)
SpringAiDocumentExtractor → Spring AI ChatClient + BeanOutputConverter<StructuredInvoiceExtraction> → LLM (OpenAI compatible)
  ↓ fallback deterministic advisory when SPRING_AI_API_KEY=dummy / ChatClient unavailable (keeps tests offline)
DocumentExtraction (one row per document UNIQUE document_id, status PENDING→PROCESSING→COMPLETED/FAILED, model+promptVersion+rawResponse+structuredData+confidence preserved)
```
Provider calls never contain secrets in logs (`sanitizeRawResponse`, no INFO raw), never scattered prompt strings (`PromptConstants.invoice-extraction-v1` central).

**OCR → AI Flow**
1. `POST /api/v1/documents` → `UPLOADED`
2. `POST /api/v1/documents/{id}/ocr` → `PROCESSING→PROCESSED` + `OcrResult COMPLETED` with `extractedText`
3. `POST /api/v1/documents/{id}/extraction` → validates OCR `COMPLETED` + non-empty text, sets extraction `PROCESSING`, calls `AiDocumentExtractor`, validates structured output, persists `COMPLETED`/`FAILED` with `model`, `promptVersion`, `structuredData` (JSON LONGTEXT), `rawResponse` (LONGTEXT internal), `confidence` (DECIMAL 5,4 0-1 or null), `errorMessage` (2000). Re-processing `COMPLETED/FAILED→PROCESSING→COMPLETED/FAILED` reuses same row (clears old data). `PROCESSING` second POST → `409`.
4. `GET /api/v1/documents/{id}/extraction` → tenant-isolated read (all authenticated roles).

**Supported Document Types** (derived from `Document.documentType`, no LLM auto-classification)
- `INVOICE` → invoice-oriented schema
- `RECEIPT` → receipt-oriented extraction
- `EXPENSE_RECEIPT` → expense-oriented extraction
- `OTHER` → only generally identifiable financial fields, others null

**Structured Schema** `StructuredInvoiceExtraction` (advisory, null if not present, never invented):
`documentType, invoiceNumber, invoiceDate (YYYY-MM-DD), dueDate, currency (3 uppercase), vendorName, vendorTaxIdentifier, vendorEmail, vendorPhone, customerName, customerTaxIdentifier, subtotal, taxAmount, discountAmount, totalAmount, paymentTerms, notes, lineItems[]`
Line item: `description, quantity, unitPrice, taxRate, taxAmount, discountAmount, lineTotal`
Numeric `BigDecimal`; monetary/quantity >=0, `taxRate` 0-100, `currency` 3 letters, dates ISO, `confidence` 0-1 else null, invalid → `FAILED` (no accounting write).

**Prompt** `invoice-extraction-v1` (`ai/PromptConstants.java:9`):
```
You are a document data extraction system.
INPUT: OCR text from a financial document.
RULES:
- Extract only information supported by OCR text. Never invent. Use null for unavailable.
- Preserve exact invoice/reference identifiers, monetary values, dates (YYYY-MM-DD), currency.
- Return structured JSON matching schema, no explanations.
- Do not create accounting entries, do not make business decisions, do not guess tax rates/accounts/vendors/customers/payment status.
- Do not calculate unless source clearly supports.
```
Version persisted per row (`promptVersion`), centralized `PromptConstants.buildUserPrompt()`.

**Configuration** (env-driven, no secrets committed):
- `SPRING_AI_API_KEY` → `spring.ai.openai.api-key` (dummy fallback, lazy invocation)
- `SPRING_AI_MODEL` → `spring.ai.openai.chat.options.model` (`gpt-4o-mini` default)
- `SPRING_AI_PROVIDER` → future provider switch (openai/azure/anthropic)
Tests use `FakeAiDocumentExtractor` (`@Profile test` `@Primary`) deterministic without network:
`INV-001 + ABC Supplies` → `invoiceNumber=INV-001, vendor=ABC Supplies, subtotal 1000, tax 180, total 1180`. `corrupt/invalid` → simulated failure → `FAILED`.

**API**
- `POST /api/v1/documents/{documentId}/extraction` → `200 DocumentExtractionResponse` (`id, documentId, extractionType, status, model, promptVersion, structuredData, confidence, processingStarted/CompletedAt, errorMessage, createdAt/updatedAt`) — rawResponse internal not exposed. `400` if OCR not `COMPLETED`/empty, `401` unauthenticated, `403` VIEWER/EMPLOYEE cannot POST, `404` cross-tenant/doc not found, `409` already `PROCESSING`.
- `GET /api/v1/documents/{documentId}/extraction` → `200` advisory data (same shape), `404` if not found/cross-tenant, `403` insufficient, `401` unauthenticated.

**Example**
```bash
# after OCR Completed
curl -X POST http://localhost:8080/api/v1/documents/$ID/extraction \
  -H "Authorization: Bearer $TOKEN"
# → 200 {"id":"...","documentId":"...","extractionType":"INVOICE","status":"COMPLETED","model":"fake-model-v1","promptVersion":"invoice-extraction-v1","structuredData":{"documentType":"INVOICE","invoiceNumber":"INV-001","invoiceDate":"2026-01-15","dueDate":"2026-02-15","currency":"INR","vendorName":"ABC Supplies","vendorTaxIdentifier":"GSTIN123","vendorEmail":"abc@supplies.com","vendorPhone":"1234567890","customerName":"Acme Corp","customerTaxIdentifier":"GSTIN999","subtotal":1000.00,"taxAmount":180.00,"discountAmount":0.00,"totalAmount":1180.00,"paymentTerms":"Net 30","notes":"Test invoice","lineItems":[{"description":"Item A","quantity":2,"unitPrice":500.00,"taxRate":10,"taxAmount":100.00,"discountAmount":0,"lineTotal":1100.00}]},"confidence":null,"processingStartedAt":"...","processingCompletedAt":"...","createdAt":"...","updatedAt":"..."}

curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/documents/$ID/extraction
# → same 200

# cross-tenant → 404, processing → 409, OCR PENDING → 400 "OCR not completed"
```

**Extraction Lifecycle** `DocumentExtractionStatus` `PENDING→PROCESSING→COMPLETED` or `PENDING→PROCESSING→FAILED`, reprocess `FAILED/COMPLETED→PROCESSING→...`, unique `document_id`, indexes `organization_id, status, extraction_type`.

**Validation** deterministic after AI: monetary/quantity/lineTotal/tax/discount >=0, taxRate 0-100, currency 3 uppercase if present, dates ISO YYYY-MM-DD, confidence 0-1; failure → `FAILED` persisted with sanitized error (no stack/path/API key, 1000 chars, `[path]` redacted), no partial accounting.

**Security & Advisory**
- Advisory only. Never auto-creates Vendor/Customer/Invoice/Expense/JournalEntry, never posts, never selects Account, never changes balances.
- Never expose `apiKey`, `authorization`, `storageKey`, temp paths, stack traces. Never log raw OCR/AI at INFO. `rawResponse` LONGTEXT internal, not in API.
- Tenant isolation enforced via `OrganizationMembership` lookup, never trust `organizationId` from body. Document/Ocr/Extraction all check `accessibleOrganizationIds`.
- Document status unchanged by extraction (`UPLOADED→PROCESSING→PROCESSED/FAILED` remains OCR-owned).

**Flyway V14** `V14__create_document_extractions_table.sql` MySQL 8.x:
`id CHAR(36) PK, document_id CHAR(36) UNIQUE FK documents ON DELETE CASCADE, organization_id CHAR(36) FK organizations, extraction_type/status VARCHAR(30), model 100, prompt_version 50, raw_response LONGTEXT, structured_data LONGTEXT (JSON validated), confidence DECIMAL(5,4), error_message 2000, processing_started/completed_at DATETIME(6), created/updated DATETIME(6), indexes org/status/type/document`. `validate` mode, `create-drop` in test (H2 MODE=MySQL).

**No Automatic Accounting** — even with `vendor ABC Supplies, INV-001, 1180` identified, service does NOT create records; human review milestone will use advisory data deterministically.

## Human Review & Approval Workflow (V15)

**Overview** — Human boundary on top of advisory AI extraction. User opens AI result, corrects fields, approves/rejects; system records who/when/comment and preserves AI provenance. **Approval confirms human review of extracted data. It does not automatically create or post accounting transactions.** No `Invoice`/`Expense`/`Vendor`/`Customer`/`JournalEntry` created on approval (future milestones may consume `APPROVED` data).

**AI → Human Review Architecture**
```
Document (UPLOADED→PROCESSED via OCR)
  ↓ extractedText → DocumentExtraction (PENDING→PROCESSING→COMPLETED advisory, UNIQUE document_id)
    ↓ structuredData (JSON LONGTEXT, validated, never overwritten)
      DocumentReview (UNIQUE extraction_id, PENDING→APPROVED/REJECTED, reviewedData JSON, provenance preserved)
        ↓
      DocumentReviewController → DocumentReviewService (tenant + RBAC + state + validation + optimistic locking)
        ↓ repository: DocumentReview + Document + DocumentExtraction + User + Membership
```
`DocumentReview` references `Organization, Document, Extraction (OneToOne UNIQUE), User reviewedBy` plus `reviewedData LONGTEXT`, `reviewComment 2000`, `reviewedAt`, `version @Version` for concurrency, indexes `organization/document/extraction/status`. Original `DocumentExtraction.structuredData` never mutated — `reviewedData` is separate copy, editable.

**Review Lifecycle** `DocumentReviewStatus`:
- `PENDING → APPROVED` (terminal, no `APPROVED→PENDING/REJECTED`)
- `PENDING → REJECTED`
- `REJECTED → PENDING` via explicit `POST /review/reopen` (clears `reviewedBy/reviewedAt`, keeps `reviewedData`, allows re-edit/approve)
New AI `COMPLETED` invalidates prior approval: `DocumentExtractionService` deletes existing `DocumentReview` on reprocessing start (`findByExtractionId` → `delete+flush`), so `GET /review` → `404` until new `POST /review`; `APPROVED` never carries over to materially new AI output. One review per extraction (`UNIQUE extraction_id`), no duplicate workflows.

**API**
- `POST /api/v1/documents/{documentId}/review` — create/start review (requires `COMPLETED` extraction with `structuredData`, `OWNER/ADMIN/ACCOUNTANT`; idempotent return existing if present; `EMPLOYEE/VIEWER→403`, cross-tenant `404`, missing `401`, not completed `400`)
- `GET /api/v1/documents/{documentId}/review` — read (all roles `OWNER/ADMIN/ACCOUNTANT/EMPLOYEE/VIEWER`, `404` if none/cross-tenant). Returns `id, documentId, extractionId, status, originalAiData (from extraction), reviewedData (from review), reviewedBy {id,firstName,lastName,email}, reviewedAt, reviewComment, createdAt, updatedAt` — never `passwordHash/JWT/storageKey/path/stack`.
- `PUT /api/v1/documents/{documentId}/review` — edit `reviewedData` while `PENDING` or `REJECTED`; `APPROVED→409` (`DocumentReviewAlreadyApprovedException`), validated deterministically, original AI unchanged. Body `{reviewedData: StructuredInvoiceExtraction}` (`@Valid @NotNull`).
- `POST /api/v1/documents/{documentId}/review/approve` — approve `PENDING` only, validates `reviewedData`, atomically `APPROVED + reviewedBy + reviewedAt + optional comment`, `400` if validation fails (remains `PENDING`), `409` if already `APPROVED`, `404` cross-tenant. Body optional `{"reviewComment":"..."}` (≤2000).
- `POST /api/v1/documents/{documentId}/review/reject` — reject `PENDING` → `REJECTED` with reviewer metadata + optional comment, same auth/codes.
- `POST /api/v1/documents/{documentId}/review/reopen` — `REJECTED→PENDING`, clears `reviewedBy/reviewedAt`, `400` if not `REJECTED`, `403` for `VIEWER/EMPLOYEE`.

**Example**
```bash
# prerequisites: document uploaded, OCR COMPLETED, extraction COMPLETED
curl -X POST http://localhost:8080/api/v1/documents/$ID/review -H "Authorization: Bearer $TOKEN"
# → 200 {"id":"...","documentId":"...","extractionId":"...","status":"PENDING","originalAiData":{"invoiceNumber":"INV-001","totalAmount":1180,...},"reviewedData":{"invoiceNumber":"INV-001","totalAmount":1180,...},"reviewedBy":null,...}

# correct
curl -X PUT http://localhost:8080/api/v1/documents/$ID/review -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"reviewedData":{"documentType":"INVOICE","invoiceNumber":"INV-001","invoiceDate":"2026-01-15","currency":"INR","vendorName":"ABC Supplies","subtotal":1000,"taxAmount":180,"totalAmount":1200,"lineItems":[{"description":"Item","quantity":2,"unitPrice":500,"taxRate":10,"taxAmount":100,"discountAmount":0,"lineTotal":1100}]}}'
# → 200 reviewedData.totalAmount=1200, originalAiData.totalAmount still 1180

curl -X POST http://localhost:8080/api/v1/documents/$ID/review/approve -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"reviewComment":"confirmed"}'
# → 200 {"status":"APPROVED","reviewedBy":{"id":"...","firstName":"...","email":"..."},"reviewedAt":"...","reviewComment":"confirmed"}

# rejection flow
curl -X POST http://localhost:8080/api/v1/documents/$ID/review/reject -H "Authorization: Bearer $TOKEN" -d '{"reviewComment":"wrong vendor"}'
curl -X POST http://localhost:8080/api/v1/documents/$ID/review/reopen -H "Authorization: Bearer $TOKEN" # → PENDING

# viewer can GET but not PUT/approve
curl -H "Authorization: Bearer $VIEWER_TOKEN" http://localhost:8080/api/v1/documents/$ID/review # 200
curl -X POST http://localhost:8080/api/v1/documents/$ID/review/approve -H "Authorization: Bearer $VIEWER_TOKEN" # 403
```

**Correction & Provenance**
- Correction edits only `DocumentReview.reviewedData` (validated JSON); `DocumentExtraction.structuredData` remains as AI source.
- `GET` exposes both: `originalAiData` vs `reviewedData` to show what AI extracted vs human changed.
- `who/when`: `reviewedBy` (User KYC, no secret), `reviewedAt` (Instant), `reviewComment` (2000). No full audit log yet; review entity is sufficient.

**Validation (reuse V14 principles)**
- `subtotal/tax/discount/total/quantity/unitPrice/taxAmount/discount/lineTotal >=0`, `taxRate 0-100`, `currency` 3 uppercase, `dates` ISO `YYYY-MM-DD`, `invoiceNumber` ≤100, `line description` ≤500, `confidence` 0-1 if retained, malformed JSON → `400`, never silent fix.

**Authorization**
- `create/edit/approve/reject/reopen`: `OWNER,ADMIN,ACCOUNTANT` (`requireWritePermission` checks role not `VIEWER/EMPLOYEE`).
- `GET`: `OWNER,ADMIN,ACCOUNTANT,EMPLOYEE,VIEWER`.
- `401` missing, `403` insufficient, `404` cross-tenant (never leak existence).

**Concurrency**
- `@Version Long version` on `DocumentReview` + `GlobalExceptionHandler` for `ObjectOptimisticLockingFailureException`/`OptimisticLockException` → `409 Concurrent modification`. Approve/reject are transactional state-checked, deterministic final state.

**Reprocessing Behavior**
- `V14` reuses same `document_extractions` row; `V15` adds invariant `NEW AI ≠ OLD APPROVAL` via `DocumentExtractionService.java:105` `reviewRepository.findByExtractionId(...).ifPresent(delete+flush)` at `PROCESSING` start, so `APPROVED` cannot survive new AI result. Manual verification: `approve → reprocess extraction → GET review 404 → POST review → PENDING`.

**Security**
- Never expose `passwordHash/JWT/apiKey/storageKey/path/stack`; never log full OCR/AI/reviewed data at INFO; `reviewComment` not logged at INFO; tenant isolation via `accessibleOrganizationIds` + organization FKs.

**Flyway V15** `V15__create_document_reviews_table.sql` MySQL 8.x: `id CHAR(36) PK, organization_id FK, document_id FK, extraction_id CHAR(36) UNIQUE FK, status VARCHAR(30), reviewed_data LONGTEXT, reviewed_by FK SET NULL, reviewed_at DATETIME(6), review_comment 2000, version BIGINT, created_at DATETIME(6), updated_at DATETIME(6), indexes org/doc/extraction/status, InnoDB utf8mb4_unicode_ci`, `validate` mode, `create-drop` in test (H2 MODE=MySQL).

**Approval Semantics** — `APPROVED` means `human reviewed and approved extracted data`; future milestones may consume approved data but this milestone never creates/posts accounting transactions.

## Kafka-Based Asynchronous Document Processing (V16)

**Architecture** — Kafka is an orchestration/transport layer only. Workers delegate to existing business logic; no OCR/AI logic is duplicated in consumers.

```
Upload Document
  ↓ stored
POST /documents/{id}/ocr → 202 PENDING → accounting.document.ocr
  ↓ DocumentOcrKafkaConsumer → OcrService.processOcr → OcrProvider
OCR COMPLETED → auto-publish accounting.document.extraction
OCR FAILED → no extraction event
  ↓ DocumentExtractionKafkaConsumer → DocumentExtractionService.processExtraction → AiDocumentExtractor
Extraction COMPLETED/FAILED (advisory) → Human Review (manual, never auto-approved)
```

* `DocumentOcrKafkaConsumer` → `OcrService.processOcr` → `OcrProvider` (Tesseract/Fake)
* `DocumentExtractionKafkaConsumer` → `DocumentExtractionService.processExtraction` → `AiDocumentExtractor` (Spring AI/Fake)
* HTTP (`requestOcr`/`requestExtraction`) only validates, sets PENDING, publishes; workers do expensive work.

**Topics** (`kafka/KafkaTopics.java` constants, never scattered strings):
* `accounting.document.ocr` — OCR jobs
* `accounting.document.extraction` — AI extraction jobs
* `accounting.document.processing.dlq` — permanently failed messages (via `DeadLetterPublishingRecoverer`)

**Consumer groups** (explicit, configurable):
* `accounting-document-ocr-workers`
* `accounting-document-extraction-workers`

**Event contracts** (JSON, versioned, `eventVersion=1`, no Java serialization):
* `DocumentOcrRequestedEvent(eventId, documentId, organizationId, requestedByUserId, eventVersion, occurredAt)`
* `DocumentExtractionRequestedEvent(...)` — same shape
* `DocumentProcessingDlqEvent(originalEventId, documentId, organizationId, processingType, failureReason≤1000, occurredAt)`
* Only identifiers + metadata. Never: OCR text, binary, storageKey/paths, JWT, passwordHash, API keys, raw AI/invoice contents.

**Producer** (`DocumentProcessingEventPublisher` via `KafkaTemplate<String,Object>`):
* `publishOcrRequested(...)` → OCR topic, key=`documentId` (ordering)
* `publishExtractionRequested(...)` → extraction topic
* Synchronous `future.get(10s)` so publish failure rolls back the PENDING transaction (no stuck state). No `KafkaTemplate` calls outside publisher.

**APIs** (polling, no WebSockets):
* `POST /api/v1/documents/{id}/ocr` → `202 {documentId, operationId, status:PENDING, message:"OCR processing has been queued"}`. Requires OWNER/ADMIN/ACCOUNTANT; EMPLOYEE/VIEWER→403; cross-tenant→404; PROCESSING→409.
* `POST /api/v1/documents/{id}/extraction` → `202 {status:PENDING, message:"AI extraction has been queued"}`. Requires OCR COMPLETED + non-empty text else 400.
* `GET .../ocr`, `GET .../extraction`, `GET .../review` show current async status (`PENDING/PROCESSING/COMPLETED/FAILED`).

Example:
```bash
curl -X POST http://localhost:8080/api/v1/documents/$ID/ocr -H "Authorization: Bearer $TOKEN"
# → 202 {"documentId":"...","operationId":"...","status":"PENDING","message":"OCR processing has been queued"}
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/documents/$ID/ocr
# → 200 {"status":"PROCESSING"} then {"status":"COMPLETED","extractedText":"..."}
```

**Lifecycle**:
* OCR: `PENDING (HTTP)` → `PROCESSING (worker)` → `COMPLETED/FAILED`; Document `UPLOADED→PROCESSING→PROCESSED/FAILED`.
* Extraction: `PENDING→PROCESSING→COMPLETED/FAILED`; Document status unchanged by extraction (stays OCR-owned).
* Reprocess `FAILED/COMPLETED→PENDING→...`; new extraction deletes prior `DocumentReview` so old APPROVAL never carries over.

**Idempotency**: workers check `document_id UNIQUE` row; `COMPLETED` duplicate → ignored; `PROCESSING` duplicate → ignored; `FAILED/PENDING` → retry to `PROCESSING`. No duplicate rows.

**Tenant safety**: consumers have no JWT; event carries `organizationId/documentId/requestedByUserId`; worker verifies `document.organization.id == event.organizationId` else `IllegalArgumentException` (DLQ, no retry). Never trust event org blindly; safe logs only (eventId/documentId/orgId/status, never OCR/AI/JWT/paths).

**Retries/DLQ**: `DefaultErrorHandler` + `FixedBackOff(1000ms, 3 attempts)`; `IllegalArgumentException` (invalid, not-found, tenant mismatch, OCR-incomplete, validation) not retryable → DLQ immediately; transient (provider/DB/Kafka) retried 3× then DLQ. DLQ payload is sanitized metadata only.

**Config** (env overrides, `application.properties:37-48`, `KafkaProperties` prefix `app.kafka`):
| Variable | Default | Description |
|---|---|---|
| `KAFKA_ENABLED` / `app.kafka.enabled` | `true` (dev), `false` (test) | kill-switch; tests run without broker |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | broker |
| `KAFKA_TOPIC_OCR` | `accounting.document.ocr` | OCR topic |
| `KAFKA_TOPIC_EXTRACTION` | `accounting.document.extraction` | extraction topic |
| `KAFKA_TOPIC_DLQ` | `accounting.document.processing.dlq` | DLQ |
| `KAFKA_CONSUMER_OCR_GROUP` | `accounting-document-ocr-workers` | OCR group |
| `KAFKA_CONSUMER_EXTRACTION_GROUP` | `accounting-document-extraction-workers` | extraction group |
Serialization: producer `JsonSerializer`, consumer `JsonDeserializer` with `trusted-packages=com.example.accounting.kafka.event`, `USE_TYPE_INFO_HEADERS=false`.

**Local setup** (host-run app):
```bash
docker compose up kafka -d   # KRaft; host listener is localhost:29092
KAFKA_ENABLED=true KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./mvnw spring-boot:run
# Inside Compose the app uses KAFKA_BOOTSTRAP_SERVERS=kafka:9092 (service name).
# upload → POST ocr (202) → worker logs OCR queued/started/completed → auto extraction → review manually
# without Kafka: app still starts; queue calls no-op (test profile); drive workers via service in tests
```

**Security/accounting safety**: Kafka never creates invoices/expenses/journal entries, never posts, never approves reviews, never bypasses tenant/RBAC/validation. Approved review does NOT create accounting entries in this milestone.

**Limitations**: no transactional outbox (publish-then-commit with rollback on publish failure); no Redis/reports/AI-assistant/payments/reconciliation/bank integrations; manual Kafka verification requires local broker.

## Redis-Based Caching & Performance Optimization (V17)

**Purpose** — Redis is a performance layer only. The MySQL database remains the source of truth. Caching is limited to high-read, relatively stable, tenant-scoped data (chart of accounts, vendors, customers, organization metadata). No financial transaction state, balances, reports, or document/AI contents are cached.

**Dependencies** (`pom.xml`, Spring Boot-managed versions, no manual version pins):
- `spring-boot-starter-data-redis` (Lettuce client, JSON serialization)
- `spring-boot-starter-cache` (Spring Cache abstraction)

**Configuration** (env-driven, `application.properties:49+`):
| Variable | Default | Description |
|---|---|---|
| `REDIS_ENABLED` / `app.redis.enabled` | `false` | Master kill-switch; tests always run with `false` |
| `REDIS_HOST` / `spring.data.redis.host` | `localhost` | Redis host |
| `REDIS_PORT` / `spring.data.redis.port` | `6379` | Redis port |
| `REDIS_PASSWORD` / `spring.data.redis.password` | *(empty)* | Redis password |
| `REDIS_TTL_ORG` | `600` | Organization metadata TTL (s, ~10 min) |
| `REDIS_TTL_ACCOUNT_TREE` | `600` | Account tree TTL (s, ~10 min) |
| `REDIS_TTL_ACCOUNT_LIST` | `600` | Account list/search TTL (s, ~10 min) |
| `REDIS_TTL_ACCOUNT_ITEM` | `600` | Single account TTL (s) |
| `REDIS_TTL_VENDOR_LIST` | `300` | Vendor list TTL (s, ~5 min) |
| `REDIS_TTL_VENDOR_ITEM` | `300` | Single vendor TTL (s) |
| `REDIS_TTL_CUSTOMER_LIST` | `300` | Customer list TTL (s, ~5 min) |
| `REDIS_TTL_CUSTOMER_ITEM` | `300` | Single customer TTL (s) |

TTLs are centralized in `cache/RedisProperties.java` (prefix `app.redis`); no hardcoded durations in services. No infinite TTLs. `spring.data.redis.repositories.enabled=false` (no Redis repositories; CacheManager/Template only).

**Local Redis startup** (optional; not required for tests):
```bash
docker compose up redis -d   # redis:7.4, localhost:6379 (see docker-compose.yml)
REDIS_ENABLED=true REDIS_HOST=localhost ./mvnw spring-boot:run
# APIs behave identically; reads are served from Redis when cache is warm.
```

**Redis-disabled behavior** (`REDIS_ENABLED=false`, the default and always in `test` profile):
- Application starts, APIs, and tests work without any Redis server.
- A `ConcurrentMapCacheManager` fallback keeps the same tenant-safe caching logic active in-memory, so caching behavior (keys, isolation, invalidation) is still exercised by tests.
- No business logic is duplicated for disabled mode; services call `TenantCacheService` which degrades to empty-cache/database reads.

**Cached modules** (all DTOs, never entities; APIs unchanged, caching is transparent):
- Chart of accounts: `GET /api/v1/accounts/tree`, `GET /api/v1/accounts` (all search/type/parent/pagination combos), `GET /api/v1/accounts/{id}`
- Vendors: `GET /api/v1/vendors`, `GET /api/v1/vendors/{id}`
- Customers: `GET /api/v1/customers`, `GET /api/v1/customers/{id}`
- Organization metadata: `GET /api/v1/organizations/{id}` (controller validates membership via `OrganizationAccessService.requireMembership` before the cached service call)

Explicitly NOT cached: journal entries/balances, posted transactions, expense/invoice accounting results, financial reports, reconciliation/payment state, document contents, OCR text, LLM responses, extraction/review data, membership authorization decisions.

**Tenant-safe cache key strategy** (`cache/CacheKeyBuilder.java`, `cache/CacheNames.java`):
- Every key starts with `org:{organizationId}:` (e.g. `org:{id}:accounts:tree`, `org:{id}:vendors:list:s=...:pg=0:sz=20:...`). There is no API to build a key without an organization ID.
- Keys are built only from organization IDs resolved through the authenticated user's membership (never from client-supplied org IDs).
- List keys embed every result-shaping parameter (search, type, parent, page, size, sort) so different queries never share a key; multi-org membership sets are sorted for a stable key.
- `TenantCacheService` rejects any key not starting with `org:` with `IllegalArgumentException`.

**Invalidation** (correctness over hit rate; always org-namespaced, never clears the whole DB):
- Account create/update/deactivate → evict that org's `accounts-tree` + `accounts-list` namespaces (+ exact `accounts-item` key on update/deactivate).
- Vendor create/update/deactivate → evict that org's `vendors-list` namespace (+ exact `vendors-item` key).
- Customer create/update/deactivate → same for `customers-*`.
- Organization metadata has an explicit `evictOrganizationCache(id)` hook for future metadata updates.
- Membership/authorization lookups are intentionally NOT cached (stale cache must never grant access after membership removal).

**Redis failure behavior**:
- Cache reads/writes/evictions are wrapped in try/catch; any Redis error is logged (without secrets/keys) and the request falls back to database access.
- A `CacheErrorHandler` swallows annotation-cache errors the same way.
- Writes always go to the database first; cache only accelerates subsequent reads. A Redis outage cannot break accounting correctness and never leaks connection details through APIs.

**Security considerations**:
- Reuses existing Spring Security/JWT + membership model; no second auth system.
- Unauthenticated requests are rejected by the security filter before any cached data is touched (verified by test).
- Cached payloads are member-safe DTOs only (no passwords, JWTs, storage keys, OCR/AI text).

**Testing approach** (no real Redis server required; `mvn clean test` covers all):
- `CacheKeyBuilderTest` — keys contain org ID, differ per org/parameter, reject null orgs.
- `TenantCacheServiceTest` — hit/miss, non-tenant keys rejected, per-org eviction leaves other orgs intact, in-memory fallback active.
- `AccountServiceCacheTest` / `VendorCustomerCacheTest` / `OrganizationCacheTest` — mocked repositories prove 2nd identical read hits cache (repo called once) and mutations force reload.
- `CacheTenantIsolationIntegrationTest` — two-org MockMvc suite: isolated trees, same-name search isolation, vendor/customer isolation, A-mutation keeps B cache valid, unauthenticated access denied despite warm cache.

## Reports & Analytics API (V18)

Read-only, deterministic financial reporting over posted accounting data. No AI/LLM is involved; all figures are computed with JPQL aggregation + Java `BigDecimal` (never floating point). Report generation never mutates accounting data.

**Endpoints** (`report/ReportController.java`, base `/api/v1/reports`):

| Endpoint | Parameters | Data source | Returns |
|---|---|---|---|
| `GET /trial-balance` | `fromDate`, `toDate` (inclusive, ISO `YYYY-MM-DD`) | POSTED journal lines in range | per-account `debitTotal/creditTotal/netBalance` + `totalDebits/totalCredits/totalDifference` |
| `GET /general-ledger` | `accountId`, `fromDate`, `toDate` + pagination | POSTED lines of one account, ordered by entryDate, entryNumber, line ID | paged line details (entry number/date, debit/credit, descriptions) |
| `GET /profit-and-loss` | `fromDate`, `toDate` | POSTED lines on REVENUE/EXPENSE accounts | revenue/expense lines + `totalRevenue/totalExpenses/netProfit` (`netProfit = revenue - expenses`) |
| `GET /balance-sheet` | `asOfDate` (inclusive) | POSTED lines dated `<= asOfDate` on ASSET/LIABILITY/EQUITY accounts | grouped lines + `totalAssets/totalLiabilities/totalEquity` |
| `GET /expenses` | `fromDate`, `toDate`, optional `vendorId` | RECORDED Expense records (authoritative; journals excluded to avoid double-counting) | by-account and by-vendor totals + grand total + count |
| `GET /invoices` | `fromDate`, `toDate`, optional `customerId` | ISSUED/PARTIALLY_PAID/PAID/OVERDUE invoices (DRAFT/VOID excluded) | `invoiceCount/totalInvoiced/totalPaid/totalOutstanding` |
| `GET /dashboard` | `fromDate`, `toDate` | P&L + invoice aggregates + active vendor/customer counts + recorded-expense count | compact summary (revenue, expenses, net profit, invoiced, outstanding, counts) |

**Posted-entry rules**: journal-based reports include only `POSTED` entries (`DRAFT`/`VOID` excluded); expenses only `RECORDED`; invoices only issued-type statuses. Date ranges are inclusive; `fromDate <= toDate` enforced (violation → HTTP 400). No timezone conversion on `LocalDate` accounting dates.

**Balance calculation rules** (`ReportService.netBalance`, documented in code): ASSET/EXPENSE are debit-normal (`debits - credits`); LIABILITY/EQUITY/REVENUE are credit-normal (`credits - debits`). Trial balance reports actual `totalDebits/totalCredits` and their difference as-is (never silently "fixed"). Balance sheet equity is the balance of EQUITY-type accounts only: no opening balances are invented and no retained-earnings/closing logic is applied (the model has no closing entries), so `assets` may differ from `liabilities + equity` by the period's net profit — this is reported honestly, not adjusted.

**Invoice payment limitation**: payment processing is not implemented, so `amountPaid`/`totalPaid` report stored values (normally zero) and `totalOutstanding` equals invoiced amounts. Not presented as paid.

**Tenant isolation**: no organization ID is accepted from clients; all queries filter by the caller's validated membership org IDs (`j.organization.id IN :orgIds`, lines via parent joins). Cross-tenant account/vendor/customer IDs return 404 (established convention, never 403/leak). All authenticated roles (OWNER/ADMIN/ACCOUNTANT/EMPLOYEE/VIEWER) may read; unauthenticated → 401.

**Redis caching decision**: reports are intentionally NOT cached. Financial figures must never go stale; correctness outranks hit rate. `TenantCacheService` is not used by `ReportService`.

**Query/index design**: aggregation runs in the database via JPQL (`SUM`/`COUNT` + `GROUP BY`) in `report/ReportRepository.java` (portable, no native SQL). Existing indexes already cover the access paths (`journal_entries(org,status)`, `(org,entry_date)`, `expenses(org,status)/(org,expense_date)/(org,vendor)`, `invoices(org,status)/(org,customer)/(org,invoice_date)`), so no Flyway migration was needed (schema remains V15, `ddl-auto=validate` intact).

**Testing** (H2, no MySQL/Redis required): `ReportControllerTest` (trial balance incl. balanced/draft/void/future/date-filter cases, ledger ordering/filtering, validation 400s, 401s, all-5-roles 200s), `FinancialStatementsTest` (P&L + balance sheet incl. as-of semantics), `OperationalReportsTest` (expense/invoice/dashboard incl. no-double-count and payment-limitation assertions), `ReportTenantIsolationTest` (two orgs with identical codes/names/numbers remain fully isolated; guessed cross-tenant IDs → 404).

## AI Financial Assistant (V19)

Read-only natural-language assistant over deterministic accounting data (`assistant/` package). The LLM explains figures; it never computes them and can never change data: there is **no tool/function calling** — intent dispatch to reporting services happens server-side with membership-derived organization context.

**Endpoint**: `POST /api/v1/assistant/chat` with `{"message": "..."}` (`@NotBlank`, max 2000 chars → 400 otherwise). Response: `{answer, dataUsed, period: {fromDate, toDate}, sources[]}` (e.g. sources `profit-and-loss`, `invoices`). Any authenticated member (OWNER/ADMIN/ACCOUNTANT/EMPLOYEE/VIEWER) may use it; unauthenticated → 401.

**Supported question categories** (allowlisted intents in `AssistantIntent`): REVENUE, EXPENSES, PROFIT, BALANCE_SHEET, TRIAL_BALANCE, GENERAL_LEDGER (by account code/name, cross-tenant IDs → 404), INVOICES (incl. overdue counts + per-customer outstanding), VENDOR_EXPENSES (named-vendor or top vendors), DASHBOARD. Write/out-of-scope requests ("create/pay/delete/approve/...") → safe refusal, nothing executed. Amounts use the organization's `currency` field; no FX conversion.

**Read-only safety boundary**: the assistant calls only `ReportService`/`ReportRepository` read methods. Verified by test: journal/invoice/expense/vendor/customer counts are unchanged after chat calls (including attempted write requests).

**Tenant isolation**: organization context comes only from server-side membership; message text (even "show me organization B's ...") cannot override it. All data queries carry `organization.id IN :orgIds`.

**Deterministic grounding**: each turn resolves intent (rule-based `IntentClassifier`, no LLM) + dates (deterministic `DateResolver` with explicit `Clock`: today/this week/this month/last month/quarters/years/named months/ISO and "Month D to Month D" ranges/"as of" dates) → fetches `ReportService` figures → sends figures + question to the model → validates every number in the model text against the deterministic set (numeric comparison, thousands-separator tolerant) → falls back to a template answer built from the figures on any mismatch or model failure. Key numeric sentences in fallbacks are constructed directly from report values. Ambiguous periods produce a clarification question (`dataUsed: false`) instead of an invented range. Comparisons (current vs previous equal-length period, `%` change in `BigDecimal`, zero-denominator safe) are computed in Java, never by the model. Invoice answers report stored `amountPaid` (zero until payments exist).

**Prompt design** (`AssistantPromptConstants`, version `financial-assistant-v1`, never exposed): numbers only from app data, repeat exactly, never claim actions, unavailable data stated, interpretation labeled, no financial/tax/investment advice, no prompt/tool disclosure. Reuses existing Spring AI config (`SPRING_AI_API_KEY/MODEL/PROVIDER`); dummy key or missing client → deterministic fallback, no network attempt. Tests use `FakeAssistantGenerator` (`@Profile("test") @Primary`, ECHO/HALLUCINATE/FAIL modes); no real LLM calls in tests.

**Failure/privacy posture**: model outage → controlled deterministic fallback (never a crash, never fabricated prose). Logs carry only intent/duration/success — never messages, figures, payloads, keys, or tokens. Stateless (no conversation persistence), synchronous (no Kafka), responses never cached in Redis (sensitive + fast-changing).

**Testing** (H2, no MySQL/Redis/LLM): `IntentClassifierTest` (all intents + write/unknown), `DateResolverTest` (fixed clock: relative periods, named months, ranges, as-of, ambiguous), `AssistantControllerTest` (401, 400s, all-5-roles 200s, per-intent grounding with exact figures, deterministic `%` comparison, clarification, refusals, hallucination rejection, FAIL fallback, two-org isolation, prompt-injection containment, no-mutation counts).

## Audit Logging & Audit Trail (V20)

Centralized, tenant-safe, append-only audit trail (`audit/` package + `audit_logs` table, Flyway **V16**). Accounting tables remain the source of truth; audits are accountability records only and never feed calculations.

**Entity** (`audit/AuditLog.java`, UUID id): `organization` (nullable only for unattributable pre-auth events such as unknown-email login), `actorUser` (null for Kafka/system events), `action` (`AuditAction` enum), `resourceType` (`AuditResourceType` enum), `resourceId` (nullable UUID), `description`, `beforeData`/`afterData`/`metadata` (LONGTEXT JSON via centralized Jackson `AuditLogService.toJson`, never string concatenation), `ipAddress`/`userAgent` (best-effort from `RequestAuditContext`, null outside HTTP), `success`, `createdAt`. No PUT/DELETE API exists; corrections are new events.

**Audited business events** (explicit service-boundary calls, never a Hibernate interceptor; GETs never audited):
- Auth/org/membership: `USER_CREATED`, `ORGANIZATION_CREATED`, `MEMBER_ADDED`, `LOGIN_SUCCESS`, `LOGIN_FAILURE` (unknown-email failures are org/actor-null; known-user failures carry primary org).
- Vendor/Customer/Account: `*_CREATED` (after snapshot), `*_UPDATED` (before/after safe fields), `*_DEACTIVATED` (before/active-true, after/active-false).
- Journal: `JOURNAL_ENTRY_CREATED/UPDATED/POSTED/VOIDED` with entry number/date/status/description/reference/total debit/credit/line count (no entity graphs).
- Expense: `EXPENSE_CREATED/UPDATED/RECORDED/VOIDED` with vendor/expense/payment account IDs, amount, date, status, journal entry ID, reference. Recording also produces the underlying journal's `CREATED+POSTED` rows (distinct resources, documented).
- Invoice: `INVOICE_CREATED/UPDATED/ISSUED/VOIDED` with number/dates/status/subtotal/tax/discount/total/paid/balance/customer/journal entry ID. Issuing also produces journal rows.
- Documents: `DOCUMENT_UPLOADED` (filename/content-type/size/type/status/checksum only) and `DOCUMENT_DELETED` (same snapshot, no bytes).
- OCR: `OCR_REQUESTED` (API, with document/event IDs), `OCR_COMPLETED`/`OCR_FAILED` (Kafka worker, status/page-count only — never extracted text).
- AI extraction: `DOCUMENT_EXTRACTION_REQUESTED` (API), `DOCUMENT_EXTRACTION_COMPLETED/FAILED` (worker; metadata has extraction type/model/status/confidence — never prompts, OCR text, or raw responses).
- Review: `DOCUMENT_REVIEW_CREATED/UPDATED/APPROVED/REJECTED/REOPENED` (status/document/extraction IDs + comment only — never reviewed payload data).
- Reports: `REPORT_VIEWED` for trial-balance, general-ledger, P&L, balance-sheet only (metadata: report type, date range, account ID, endpoint; never figures). Expense/invoice/dashboard summaries are intentionally not audited to bound volume.
- Assistant: `ASSISTANT_QUERY` per chat turn (metadata: intent, source report types, resolved period, success; never the question, answer, figures, or prompts).

**Transaction behavior**: business mutations call `AuditLogService.record` (default propagation) so audit commits or rolls back with the business row — a rolled-back vendor create leaves no success audit. `recordInNewTransaction` (REQUIRES_NEW) is reserved for: authentication failures (must survive the failed login), worker completion/failure records where durable evidence is wanted, and read-path events (report views, assistant queries) originating from read-only transactions. Worker audits are wrapped so audit failure never breaks Kafka processing; read-path audits are swallowed so reports/assistant never fail because auditing failed.

**Kafka idempotency**: workers (`OcrService.processOcr`, `DocumentExtractionService.processExtraction`) return early for already-COMPLETED/PROCESSING rows before any audit call, so duplicate redeliveries produce no new audit rows. Metadata carries `source=KAFKA_WORKER`, `processingType`, `documentId`, `eventId`. Retries after genuine failure are bounded by the consumer's max-retries (3), not unbounded.

**Tenant isolation**: organization comes only from server-side context (membership resolution for API, validated event + DB ownership check for workers); actor comes only from the security context (never client input). Read API has no organization filter parameter — results are scoped to `readableOrganizationIds` (OWNER/ADMIN/ACCOUNTANT only). Cross-tenant IDs return 404 (same safe convention as other reads). `AuditLogService.search` fixes ordering to `createdAt DESC, id ASC` and ignores client sort; pagination defaults to 20, 90-day default window.

**Sensitive-data policy** (`AuditSnapshots` builders take explicit scalar values, never whole entities): before/after snapshots contain only safe business fields listed above. Never stored: passwords, hashes, JWT/refresh tokens, API keys, Redis/AI secrets, file bytes, OCR text, raw LLM responses/prompts, full report payloads, questions/answers, headers, request bodies, stack traces. Tested explicitly (see below).

**Read API** (`audit/AuditLogController.java`, `/api/v1/audit-logs`): `GET /` with optional `action`, `resourceType`, `resourceId`, `actorUserId`, `success`, `fromDate`/`toDate` (ISO dates, UTC day bounds, `from<=to` else 400) + pagination; `GET /{id}` tenant-checked. DTO (`AuditLogResponse`) exposes id, organizationId, action, resourceType, resourceId, actorUserId, actorName (derived, null when absent), description, before/after/metadata (parsed JSON), ip, userAgent, success, createdAt — no secrets. Permissions: OWNER/ADMIN/ACCOUNTANT → 200; EMPLOYEE/VIEWER (with no readable org) → 403; unauthenticated → 401; invalid UUID → 400. Audit rows are never cached in Redis and never sent through Kafka.

**Indexes** (V16): `(organization_id, created_at)`, `(organization_id, resource_type, resource_id)`, `(organization_id, actor_user_id, created_at)`, `(organization_id, action, created_at)`. No over-indexing.

**Retention**: no automatic deletion or purge exists; retention policy is not yet automated (documented limitation).

**Testing** (H2, no MySQL/Redis/Kafka broker): `audit/AuditTenantIsolationTest` (7 tests: A↔B invisibility, guessed-ID 404, EMPLOYEE/VIEWER 403, OWNER/ADMIN/ACCOUNTANT 200, filter scoping, 401s, 400s) and `audit/AuditBehaviorTest` (12 tests: secrets/raw-content absence across user/invoice/expense/journal/document/assistant domains, OCR/extraction raw-data absence, question/answer absence, commit-together, rollback-leaves-nothing, failed-login-still-audited, OCR/extraction duplicate idempotency, single-audit-per-HTTP-op, REPORT_VIEWED for all four financial reports without figures, ASSISTANT_QUERY metadata-only, append-only design note). MySQL `validate` was not run manually here (no local MySQL/docker in this environment); H2 `create-drop` covers entity mapping.

## Docker & Production Hardening (V21)

Final planned backend milestone: a reproducible local Docker environment and stronger production defaults. It preserves all existing API contracts, accounting invariants, tenant isolation, authentication/RBAC, Kafka event handling, document handling, AI safety, and audit behavior. **No Flyway migration was required** (schema remains V16).

### Dockerfile design

- Multi-stage build: `maven:3.9.9-eclipse-temurin-21` builds and packages; `eclipse-temurin:21-jre-jammy` runs.
- Maven, the JDK, source code, and tests are not copied into the runtime image.
- Runtime user is non-root `app` (UID/GID `10001`), with `/app` and `/data/documents` owned by that user.
- `curl` supports the container health check; `tesseract-ocr` preserves the existing local OCR engine (no documents or OCR data are baked into the image).
- `EXPOSE 8080`; `JAVA_OPTS` uses container-aware `MaxRAMPercentage` settings; `exec java ...` keeps the JVM as PID 1 so `SIGTERM` triggers graceful shutdown.
- No credentials, `.env`, API keys, documents, build output, or local logs are copied.

Build the image without Compose:

```bash
docker build -t accounting-backend:local .
```

### Compose stack (local development only)

`docker-compose.yml` defines:

| Service | Image | Container name/host | Host access (loopback) | Persistence |
|---|---|---|---|---|
| `mysql` | `mysql:8.4` | `mysql:3306` | `127.0.0.1:3306` (local tools) | `mysql_data` volume |
| `redis` | `redis:7.4` | `redis:6379` | `127.0.0.1:6379` (local tools) | `redis_data` volume |
| `kafka` | `apache/kafka:3.8.0` (KRaft) | `kafka:9092` | `127.0.0.1:29092` (host clients) | container-local (dev) |
| `app` | built from `Dockerfile` | `app:8080` | `127.0.0.1:8080` | `app_documents` at `/data/documents` |

- Container-to-container connections use Compose service names, never `localhost`.
- Kafka advertises `PLAINTEXT://kafka:9092` to containers and `PLAINTEXT_HOST://localhost:29092` to host tools.
- MySQL, Redis, and Kafka have health checks; the app waits for MySQL and Kafka readiness.
- Host ports are bound to `127.0.0.1` only. They exist so local tools (mysql CLI, redis-cli, Kafka clients, browser) can reach the stack.
- Redis is optional: the app does not depend on the Redis container, and `REDIS_ENABLED=false` uses the existing in-memory fallback.
- **The included Compose stack is for local development and is not a complete production deployment architecture.** It does not provide TLS, secret management, high availability, external backups, or hardened Kafka/Redis/MySQL clusters.

### First-time local setup

```bash
cp .env.example .env
# Edit .env: replace every change-me / replace-with placeholder.
# Generate a real signing secret (at least 32 bytes):
openssl rand -base64 48
# Put that value in JWT_SECRET in .env.
```

Never commit `.env`; it is gitignored. `.env.example` contains placeholders only.

### Start, stop, and logs

```bash
# Build and start the full local stack
docker compose up -d --build

# Status and app logs
docker compose ps
docker compose logs -f app
docker compose logs -f mysql kafka

# Restart only the app after a code change
docker compose up -d --build app

# Stop containers but keep volumes/data
docker compose down

# DANGER: also delete MySQL, Redis, and document volumes
docker compose down -v
```

Run the app on the host against Compose infrastructure (no app container):

```bash
docker compose up -d mysql redis kafka
SPRING_PROFILES_ACTIVE=dev \
DB_HOST=localhost DB_PORT=3306 DB_NAME=accounting_db \
DB_USERNAME=accounting DB_PASSWORD='<your .env DB password>' \
REDIS_ENABLED=false \
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 \
DOCUMENT_STORAGE_PATH=./storage/documents \
./mvnw spring-boot:run
```

The default local Compose profile is `dev`. Set `SPRING_PROFILES_ACTIVE=prod` only for a production-like run with all secrets and TLS settings explicitly configured; the Compose MySQL service is not a production database.

### Environment variables

| Variable | Purpose | Required |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` (default) or `prod` | No (Compose defaults to `dev`) |
| `SERVER_PORT` | Host-published app port | No (`8080`) |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | MySQL connection | Yes in Compose |
| `DB_USERNAME`, `DB_PASSWORD` | MySQL application user/password | Yes |
| `MYSQL_ROOT_PASSWORD` | Compose-only MySQL root password | Yes in Compose |
| `JWT_SECRET` | JWT signing secret; must be strong in `prod` | Yes |
| `JWT_ACCESS_TOKEN_EXPIRATION`, `JWT_REFRESH_TOKEN_EXPIRATION` | Token lifetimes (ms) | No |
| `REDIS_ENABLED` | Enable Redis caching | No (`false`) |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis connection | Password yes in Compose |
| `KAFKA_ENABLED` | Enable asynchronous consumers/producers | No (`true`) |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka bootstrap; `kafka:9092` in Compose, `localhost:29092` on host | Yes when Kafka enabled |
| `KAFKA_TOPIC_OCR`, `KAFKA_TOPIC_EXTRACTION`, `KAFKA_TOPIC_DLQ` | Topic overrides | No |
| `KAFKA_CONSUMER_OCR_GROUP`, `KAFKA_CONSUMER_EXTRACTION_GROUP` | Consumer group overrides | No |
| `KAFKA_CONSUMER_MAX_RETRIES`, `KAFKA_CONSUMER_BACKOFF_MS` | Retry/DLQ behavior overrides | No |
| `SPRING_AI_PROVIDER`, `SPRING_AI_MODEL`, `SPRING_AI_API_KEY` | AI provider configuration | Key required by the `prod` profile; dev/test use the dummy fallback |
| `DOCUMENT_STORAGE_PATH` | Local document root | No (`/data/documents` in Compose) |
| `DOCUMENT_MAX_FILE_SIZE` | Upload/multipart limit | No (`10MB`) |
| `APP_CORS_ALLOWED_ORIGINS` | Comma-separated explicit browser origins | No (empty = disabled) |
| `JAVA_OPTS` | Container JVM memory options | No |

### Production profile and JWT fail-fast behavior

`application-prod.properties` is activated with `SPRING_PROFILES_ACTIVE=prod` and:

- Uses MySQL via `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD` with `useSSL=true` and `allowPublicKeyRetrieval=false`.
- Keeps `spring.jpa.hibernate.ddl-auto=validate`; Flyway owns schema changes. `spring.flyway.clean-disabled=true` and `validate-on-migrate=true`.
- Requires `JWT_SECRET` (`${JWT_SECRET}` with no production fallback) and sets `app.jwt.require-strong-secret=true`.
- `JwtSecretValidator` fails startup with a clear message when the secret is missing, uses a known dev/test default, contains a placeholder marker, or decodes to fewer than 32 bytes. The secret value is never logged.
- Keeps CORS disabled unless `APP_CORS_ALLOWED_ORIGINS` explicitly lists exact origins; wildcard `*` is rejected.
- Hides error stack traces/messages and Actuator details.
- Uses environment-driven HikariCP limits: `DB_POOL_MAX` (default 10), `DB_POOL_MIN_IDLE` (default 2), `DB_CONNECTION_TIMEOUT` (default 10000 ms), plus 3 s validation, 10 min idle, and 30 min max lifetime.
- Enables graceful shutdown with a 30 s shutdown phase timeout.

Generate the production secret with a cryptographically secure source such as `openssl rand -base64 48`, store it in a secrets manager, and inject it at runtime. Development and test profiles keep `require-strong-secret=false`; the test suite remains Docker-free.

### Flyway and MySQL operations

- Flyway runs automatically at application startup **before** JPA/Hibernate validates the schema (`ddl-auto=validate`).
- Existing migrations `V1`–`V16` are immutable. Never edit an applied migration; add a new `V17__...` only for a real schema change.
- Inspect migration state: `docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" accounting_db -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"'`.
- A named Docker volume is **not** a backup strategy. It protects data across container recreation only, not accidental deletion, corruption, host loss, or operator error.

#### MySQL backup and restore (local examples)

```bash
mkdir -p backups
docker compose exec -T mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers "$MYSQL_DATABASE"' \
  > "backups/mysql-$(date +%Y%m%d-%H%M%S).sql"
```

Restore into an empty/known-good database (test the restore procedure first):

```bash
docker compose stop app
cat backups/mysql-YYYYMMDD-HHMMSS.sql | \
  docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
docker compose start app
```

For production, use managed MySQL backups/point-in-time recovery, encrypt backups, store them off-host, restrict access, and rehearse restores regularly.

### Document storage persistence and backup

- The app stores files under `DOCUMENT_STORAGE_PATH`; Compose mounts the named volume `accounting-backend_app_documents` at `/data/documents`.
- The Dockerfile creates `/data/documents` owned by UID/GID `10001`, matching the non-root runtime user. For a bind mount, run `chown -R 10001:10001 ./your-documents`.
- Documents are never served as a public static directory. Access remains authenticated and tenant-checked through the document APIs.
- Existing content-type/size validation and path-traversal protection are unchanged.

Back up documents consistently with database metadata. Because the database stores document metadata/storage keys, take both snapshots while the app is paused (or use a coordinated storage/database snapshot):

```bash
docker compose stop app
docker run --rm \
  -v accounting-backend_app_documents:/data:ro \
  -v "$PWD/backups:/backup" \
  alpine:3.20 \
  sh -c 'tar czf /backup/documents-$(date +%Y%m%d-%H%M%S).tgz -C /data .'
docker compose start app
```

Restore the database and document archive before starting the app, so metadata and files refer to the same point in time:

```bash
docker compose stop app
docker run --rm \
  -v accounting-backend_app_documents:/data \
  -v "$PWD/backups:/backup" \
  alpine:3.20 \
  sh -c 'tar xzf /backup/documents-YYYYMMDD-HHMMSS.tgz -C /data'
docker compose start app
```

Do not delete the document volume independently of the database: doing so leaves metadata rows pointing at missing files.

### Kafka and Redis behavior

- Kafka topic names, consumer groups, event contracts, retry/backoff, DLQ publishing, and idempotent worker behavior are unchanged.
- The app uses `KAFKA_BOOTSTRAP_SERVERS`; Compose injects `kafka:9092` for the app container and advertises `localhost:29092` to host clients.
- Kafka being unavailable does not prevent HTTP startup or fail readiness; consumers reconnect in the background and producers surface failures without bypassing accounting rules. No outbox was added in this milestone.
- Redis remains an optional performance layer. With `REDIS_ENABLED=false`, the existing in-memory cache fallback works and no Redis server is required. Cache errors fall back to MySQL. Reports, financial figures, OCR/AI content, and authorization decisions remain uncached.

### Logging, shutdown, and HTTP security

- Console logs use a consistent ISO-8601 timestamp format. SQL logging remains at `WARN` in dev/prod; bind parameters are not logged.
- Existing application code avoids logging passwords, JWTs/refresh tokens, API keys, Authorization headers, raw prompts/responses, document bytes, OCR text, and sensitive financial payloads. Keep it that way when adding features.
- `server.shutdown=graceful` with a 30 s timeout allows in-flight HTTP work and Kafka listener shutdown to complete where practical; `exec` in the container entrypoint forwards `SIGTERM`.
- CSRF stays disabled only because the API is stateless JWT with no cookie/session authentication. Tenant isolation, membership checks, RBAC, and audit logging are unchanged.
- Only `/api/v1/health`, `/api/v1/auth/register|login|refresh`, and `/actuator/health/**` are public. Everything else requires authentication; no Actuator or audit endpoint was made public.

### Running the test suite

Tests use the `test` profile with in-memory H2 and do **not** require Docker, MySQL, Kafka, Redis, Tesseract, or an LLM. Use Java 21 (the project targets Java 21; newer JDKs can break Mockito's inline mock maker):

```bash
# macOS example
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw test
```

The complete suite is 565 tests, including the original 537 plus the new configuration/security tests for JWT fail-fast validation, production properties, Actuator probe exposure, and CORS defaults/allowlists.

### Production deployment checklist

The included Compose stack is **not** a production deployment. Before production:

1. Use managed/hardened MySQL 8.x with TLS, backups, monitoring, and least-privilege credentials; set `SPRING_PROFILES_ACTIVE=prod`.
2. Store `JWT_SECRET` and `SPRING_AI_API_KEY` in a secrets manager; never use `.env.example` values. Rotate secrets on a schedule.
3. Replace Compose Kafka/Redis with secured, replicated services and configure TLS/SASL/ACLs (Kafka) and TLS/auth (Redis). Keep `KAFKA_ENABLED`/`REDIS_ENABLED` explicit.
4. Set `APP_CORS_ALLOWED_ORIGINS` to exact frontend origins over HTTPS; never use `*`.
5. Put the app behind a TLS-terminating reverse proxy/load balancer; do not publish MySQL, Redis, Kafka, or the app port directly to the internet.
6. Persist `/data/documents` on durable storage with the correct UID/GID, and back it up consistently with MySQL.
7. Aggregate logs centrally, restrict access, set retention, and alert on errors/audit failures. Do not log request bodies or Authorization headers.
8. Pin/scan container images, run as non-root (already configured), set resource limits, and use liveness/readiness probes.
9. Rehearse MySQL and document restores; a volume snapshot alone is not a backup.

---

## Roadmap Status

The planned backend roadmap (foundation through V21 Docker/production hardening) is implemented. Any future work should add a new Flyway version and preserve the security, tenancy, accounting, asynchronous-processing, and audit invariants documented above.

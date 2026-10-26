# Order & Inventory API

A Java 21 / Spring Boot REST API for placing multi-item orders against a fixed
MySQL product catalogue. Customers can view and cancel their own orders; an
administrator can mark orders as shipped.

The project focuses on database correctness: keeping stock and order writes
atomic, handling duplicate requests, and serializing competing state changes.

## Features

- Atomic order placement with exact decimal prices and stored item snapshots.
- Customer-scoped idempotency keys: a retry returns the existing order; different
  contents with the same key return a conflict.
- Conditional SQL stock updates to prevent overselling, with rollback if a later
  item fails.
- Cancellation restores stock once; cancellation and shipment lock the same order.
- HTTP Basic authentication, role checks, owner-scoped queries and validated input.
- Real MySQL integration tests that run against two separate application JVMs.

**Stack:** Java 21, Spring Boot 4.1.1, Spring MVC/Security/JDBC, MySQL 8.4.6,
Flyway, JUnit and Testcontainers. SQL is explicit through JdbcTemplate.

[Design and trade-offs](docs/DESIGN.md) · [Tests and reproduction](docs/TESTING.md)

## Run locally

Prerequisites: JDK 21, Git, Docker with Linux containers, and PowerShell 7 for the
commands below. Maven is downloaded by the included Wrapper.

```powershell
git clone https://github.com/YemYiFoo/order-inventory.git
Set-Location order-inventory
.\scripts\setup.ps1
.\scripts\verify.ps1
docker compose up -d --build --wait
if ($LASTEXITCODE -ne 0) { throw 'Application startup failed' }
```

Setup creates an ignored `.env` with local database passwords and starts MySQL.
Verification builds the JAR and runs tests against disposable databases. Compose
then starts the API with local demo accounts and products. Existing development
data is preserved; Flyway seeds only once.

Default loopback ports: API **18080**, optional second instance **18081**, MySQL
**13306**. Override `APP_PORT`, `APP2_PORT` and `MYSQL_PORT` in `.env` before startup
if those ports are occupied.

### Try an order

Local demo users are `customer-a`, `customer-b` (CUSTOMER) and `admin` (ADMIN).
All use the demo password `Demo-only-123!`; stored passwords are BCrypt hashes.

```powershell
curl.exe -sS http://127.0.0.1:18080/api/products
$body='{"items":[{"productId":101,"quantity":2},{"productId":102,"quantity":1}]}'
[IO.File]::WriteAllText("$pwd/order.json", $body, [Text.UTF8Encoding]::new($false))
curl.exe -i -u customer-a:Demo-only-123! -H 'Content-Type: application/json' -H 'Idempotency-Key: example-001' --data-binary '@order.json' http://127.0.0.1:18080/api/orders
Remove-Item -LiteralPath ./order.json
```

A new order returns **201**, a relative `Location` header, state `PLACED` and total
`"28.20"` MYR for this example. Repeating the same request and key returns **200**
with the same order ID and no second deduction. Changing quantities with that key
returns **409**. Use a new key for a new order.

The scripted walkthrough covers placement, replay, rollback, permissions,
cancellation and shipment:

```powershell
.\scripts\demo.ps1
# Optional second application sharing the same MySQL database:
docker compose --profile second up -d --wait
# Stop project containers; retain the database volume:
.\scripts\stop.ps1
```

Each successful walkthrough leaves one shipped unit consumed. It does not reset
stock; it reports exhausted demo fixtures instead.

## Routes

| Method | Path | Access / purpose |
|---|---|---|
| GET | `/actuator/health` | Public database-aware health |
| GET | `/api/products`, `/api/products/{id}` | Public catalogue |
| POST | `/api/orders` | CUSTOMER; requires `Idempotency-Key` |
| GET | `/api/orders/{id}` | CUSTOMER; own order |
| GET | `/api/orders?page=0&size=20` | CUSTOMER; own history |
| POST | `/api/orders/{id}/cancel` | CUSTOMER; own order; empty body |
| POST | `/api/admin/orders/{id}/ship` | ADMIN; empty body |

Order bodies accept 1–20 distinct positive product IDs and integer quantities
1–1000, up to 64 KiB. Prices and customer identity come from the server. Keys are
case-sensitive, customer-scoped, and 1–128 characters from `[A-Za-z0-9._:-]`.
Money is returned as two-decimal strings; timestamps use UTC.

Repeated cancellation/shipment returns the existing state. Cancelling a shipped
order or shipping a cancelled order returns 409. Another customer's order returns
404. Errors use `{ "code": "...", "message": "..." }`; validation is 400,
unsupported content is 415, oversized input is 413, and temporary database failures
are 503 with `Retry-After: 1`. Retry an uncertain placement with the **same key**.

## Scope and limitations

This is a local learning project with one warehouse, a fixed catalogue and MYR
prices. It has no payment, restocking, frontend, Redis or microservices. HTTP Basic
over plain HTTP and disabled CSRF assume local non-browser clients; this is not a
public deployment configuration. Bounded concurrency tests demonstrate specific
correctness properties, not production throughput or universal exactly-once execution.

Developed with AI assistance for implementation, testing and review.

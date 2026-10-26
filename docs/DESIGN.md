# Design and trade-offs

## Request flow

`ApiController -> OrderInput -> OrderFacade -> OrderWriter -> OrderRepository -> MySQL`

The controller gets customer identity from Spring Security. OrderInput validates
and sorts product IDs. The facade calls a separate Spring writer bean so the call
crosses the transactional proxy. JdbcTemplate participates in that transaction.
Read-only detail, replay and history operations use OrderReader.

Schema: `app_users -> orders -> order_items <- products`.
Foreign keys, CHECK constraints and a unique `(customer_id, idempotency_key)`
constraint complement application validation. See the
[schema migration](../src/main/resources/db/migration/V1__schema.sql).

## Atomic placement

[OrderWriter.place](../src/main/java/dev/portfolio/orders/OrderWriter.java) uses
READ_COMMITTED and performs these operations in one transaction:

1. Insert the header with customer, idempotency key and canonical request.
2. For each product in ascending ID order, read its snapshot and conditionally
   deduct stock: `UPDATE products SET available_stock = available_stock - ? WHERE id = ? AND available_stock >= ?`.
3. Require one affected row, save item snapshots, and calculate the BigDecimal total.
4. Save the total and commit before the controller returns 201.

A missing or insufficient later product throws an exception through the proxy,
rolling back the header, earlier stock deductions and items. An initial stock read
alone would not protect a later write from competing requests. Sorted product
access reduces deadlock opportunities, but does not make deadlocks impossible.

## Duplicate requests

The database arbitrates the customer/key uniqueness constraint before stock is
touched. Canonical contents are sorted product/quantity pairs such as
`v1|101:2|102:1`; whitespace and input item order do not change their meaning.

A competing insert waits. After the first transaction commits, a duplicate failure
rolls back the second writer transaction. OrderFacade catches only the specific
`uk_orders_customer_key` violation, then calls OrderReader in a fresh transaction:
matching contents return 200/current order; different contents return 409.
Unrelated integrity failures remain errors.

If the first transaction rolls back, a waiter can succeed. With multiple waiters,
a deadlock victim may receive 503 and retry with the same key. There is no automatic
transaction-retry framework or in-memory deduplication map. A connection failure
during commit can leave the outcome unknown; absence of a response is not proof
of rollback. Keys remain associated with successful orders without expiry.

## Cancellation and shipment

Both operations select the owner-scoped order `FOR UPDATE`. An already-requested
terminal state returns without changing stock. Only PLACED can transition:

- PLACED -> CANCELLED: restore sorted item quantities and update state atomically.
- PLACED -> SHIPPED: update state; stock was already deducted at placement.

Competing cancellation and shipment serialize on that row. The incompatible loser
returns 409. Cancellation lock failure must roll back earlier restoration too.

## Money, reads and HTTP boundaries

- DECIMAL/BigDecimal preserve exact amounts. Items store name, SKU and price
  snapshots, so later catalogue edits do not rewrite old orders.
- History uses `(customer_id, created_at DESC, id DESC)` indexing and stable sorting.
  Page/count reads under READ_COMMITTED are not a frozen historical snapshot.
- Hikari connection acquisition is bounded to 10 seconds; InnoDB lock waits to
  5 seconds. Transient database failures escape the writer and trigger whole-operation
  rollback, even when MySQL would roll back only the failed statement.
- Placement reads at most 65,537 bytes to enforce the 64 KiB limit. Every
  Content-Encoding field is checked; unsupported encodings are rejected.
- Write routes declare JSON output before invocation. An incompatible Accept
  header returns 406 before writes, rather than failing after a successful commit.
- Ownership is enforced in SQL; role checks occur in Spring Security. Local demo
  seeds run only with the local profile.

## Why this scope

Explicit JDBC SQL exposes the transaction and lock behaviour for study. One
monolithic application and a real database are sufficient for the business rules;
no distributed cache, message queue or generic repository framework is needed.
The test suite examines committed data through independent connections and two
application processes. See [testing](TESTING.md) for scenarios and limits.

References: [Spring transaction proxies](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html),
[Spring JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html),
[InnoDB locking reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html),
[InnoDB error handling](https://dev.mysql.com/doc/refman/8.4/en/innodb-error-handling.html).

package dev.portfolio.orders;

import static dev.portfolio.orders.Models.money;

import dev.portfolio.orders.Models.Detail;
import dev.portfolio.orders.Models.History;
import dev.portfolio.orders.Models.OrderItem;
import dev.portfolio.orders.Models.Product;
import dev.portfolio.orders.Models.StoredOrder;
import dev.portfolio.orders.Models.Summary;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/** Executes parameterized SQL using the current Spring-managed connection. */
@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Product> products() {
        return jdbc.query(
                """
                SELECT *
                FROM products
                ORDER BY id
                """,
                (rows, rowNumber) ->
                        new Product(
                                rows.getLong("id"),
                                rows.getString("sku"),
                                rows.getString("name"),
                                money(rows.getBigDecimal("price")),
                                "MYR",
                                rows.getInt("available_stock")));
    }

    public Product product(long id) {
        return jdbc
                .query(
                        "SELECT * FROM products WHERE id=?",
                        (rows, rowNumber) ->
                                new Product(
                                        rows.getLong("id"),
                                        rows.getString("sku"),
                                        rows.getString("name"),
                                        money(rows.getBigDecimal("price")),
                                        "MYR",
                                        rows.getInt("available_stock")),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(ApiException::missing);
    }

    public long insertHeader(long customerId, String key, String canonical) {
        var generated = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    """
                                    INSERT INTO orders(customer_id, idempotency_key, canonical_request, status, currency, total_amount)
                                    VALUES (?, ?, ?, 'PLACED', 'MYR', 0)
                                    """,
                                    Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, customerId);
                    statement.setString(2, key);
                    statement.setString(3, canonical);
                    return statement;
                },
                generated);
        return Objects.requireNonNull(generated.getKey()).longValue();
    }

    public boolean deduct(long id, int quantity) {
        return jdbc.update(
                        """
                        UPDATE products
                        SET available_stock = available_stock - ?
                        WHERE id = ? AND available_stock >= ?
                        """,
                        quantity,
                        id,
                        quantity)
                == 1;
    }

    public void addItem(long orderId, Product product, int quantity, BigDecimal total) {
        jdbc.update(
                """
                INSERT INTO order_items(order_id, product_id, quantity, sku, name, unit_price, line_total)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                orderId,
                product.id(),
                quantity,
                product.sku(),
                product.name(),
                new BigDecimal(product.price()),
                total);
    }

    public void total(long id, BigDecimal total) {
        jdbc.update("UPDATE orders SET total_amount=? WHERE id=?", total, id);
    }

    public StoredOrder byKey(long customerId, String key) {
        return jdbc
                .query(
                        """
                        SELECT id, customer_id, canonical_request, status
                        FROM orders
                        WHERE customer_id = ? AND idempotency_key = ?
                        """,
                        (rows, rowNumber) ->
                                new StoredOrder(
                                        rows.getLong(1),
                                        rows.getLong(2),
                                        rows.getString(3),
                                        rows.getString(4)),
                        customerId,
                        key)
                .stream()
                .findFirst()
                .orElseThrow(ApiException::missing);
    }

    public StoredOrder lock(long id, Long customerId) {
        String sql =
                "SELECT id,customer_id,canonical_request,status FROM orders WHERE id=?"
                        + (customerId == null ? "" : " AND customer_id=?")
                        + " FOR UPDATE";
        Object[] args = customerId == null ? new Object[] {id} : new Object[] {id, customerId};
        return jdbc
                .query(
                        sql,
                        (rows, rowNumber) ->
                                new StoredOrder(
                                        rows.getLong(1),
                                        rows.getLong(2),
                                        rows.getString(3),
                                        rows.getString(4)),
                        args)
                .stream()
                .findFirst()
                .orElseThrow(ApiException::missing);
    }

    public Detail detail(long id, Long customerId) {
        String sql =
                "SELECT id,status,currency,total_amount,created_at FROM orders WHERE id=?"
                        + (customerId == null ? "" : " AND customer_id=?");
        Object[] args = customerId == null ? new Object[] {id} : new Object[] {id, customerId};
        Summary header =
                jdbc
                        .query(
                                sql,
                                (rows, rowNumber) ->
                                        new Summary(
                                                rows.getLong(1),
                                                rows.getString(2),
                                                rows.getString(3),
                                                money(rows.getBigDecimal(4)),
                                                rows.getTimestamp(5).toInstant()),
                                args)
                        .stream()
                        .findFirst()
                        .orElseThrow(ApiException::missing);
        return new Detail(
                header.id(),
                header.status(),
                header.currency(),
                header.totalAmount(),
                header.createdAt(),
                items(id));
    }

    public List<OrderItem> items(long id) {
        return jdbc.query(
                """
                SELECT product_id, sku, name, quantity, unit_price, line_total
                FROM order_items
                WHERE order_id = ?
                ORDER BY product_id
                """,
                (rows, rowNumber) ->
                        new OrderItem(
                                rows.getLong(1),
                                rows.getString(2),
                                rows.getString(3),
                                rows.getInt(4),
                                money(rows.getBigDecimal(5)),
                                money(rows.getBigDecimal(6))),
                id);
    }

    public void restore(long id, int quantity) {
        jdbc.update(
                "UPDATE products SET available_stock=available_stock+? WHERE id=?", quantity, id);
    }

    public void status(long id, String status) {
        jdbc.update("UPDATE orders SET status=? WHERE id=?", status, id);
    }

    public History history(long customerId, int page, int size) {
        var summaries =
                jdbc.query(
                        """
                        SELECT id, status, currency, total_amount, created_at
                        FROM orders
                        WHERE customer_id = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT ? OFFSET ?
                        """,
                        (rows, rowNumber) ->
                                new Summary(
                                        rows.getLong(1),
                                        rows.getString(2),
                                        rows.getString(3),
                                        money(rows.getBigDecimal(4)),
                                        rows.getTimestamp(5).toInstant()),
                        customerId,
                        size,
                        (long) page * size);
        long count =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM orders WHERE customer_id=?",
                                Long.class,
                                customerId));
        return new History(summaries, page, size, count);
    }
}

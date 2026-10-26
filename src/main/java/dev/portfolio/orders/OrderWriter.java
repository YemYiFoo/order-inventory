package dev.portfolio.orders;

import dev.portfolio.orders.Models.Detail;
import dev.portfolio.orders.Models.OrderRequest;
import dev.portfolio.orders.Models.Product;
import dev.portfolio.orders.Models.StoredOrder;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Owns atomic order placement and locked lifecycle transitions. */
@Service
public class OrderWriter {

    private final OrderRepository repository;

    public OrderWriter(OrderRepository repository) {
        this.repository = repository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail place(long customerId, String idempotencyKey, OrderRequest request) {
        // Insert first: database uniqueness arbitrates competing requests before stock changes.
        long orderId = repository.insertHeader(customerId, idempotencyKey, request.canonical());
        BigDecimal totalAmount = new BigDecimal("0.00");

        // OrderInput sorts products so overlapping orders acquire stock locks in the same order.
        for (var item : request.items()) {
            Product product = repository.product(item.productId());
            if (!repository.deduct(item.productId(), item.quantity())) {
                throw new ApiException(
                        409,
                        "INSUFFICIENT_STOCK",
                        "Insufficient stock for product " + item.productId());
            }

            BigDecimal lineTotal =
                    new BigDecimal(product.price())
                            .multiply(BigDecimal.valueOf(item.quantity()))
                            .setScale(2);
            totalAmount = totalAmount.add(lineTotal);
            repository.addItem(orderId, product, item.quantity(), lineTotal);
        }

        repository.total(orderId, totalAmount);
        return repository.detail(orderId, customerId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail transition(long orderId, Long customerId, String targetStatus) {
        // The locked status and stock restoration belong to this same transaction.
        StoredOrder order = repository.lock(orderId, customerId);
        if (order.status().equals(targetStatus)) {
            return repository.detail(orderId, customerId);
        }
        if (!order.status().equals("PLACED")) {
            throw new ApiException(409, "INVALID_STATE", "Order is already " + order.status());
        }

        if (targetStatus.equals("CANCELLED")) {
            for (var item : repository.items(orderId)) {
                repository.restore(item.productId(), item.quantity());
            }
        }

        repository.status(orderId, targetStatus);
        return repository.detail(orderId, customerId);
    }
}

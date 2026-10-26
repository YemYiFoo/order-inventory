package dev.portfolio.orders;

import dev.portfolio.orders.Models.Detail;
import dev.portfolio.orders.Models.History;
import dev.portfolio.orders.Models.StoredOrder;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Uses fresh read transactions for replay, owned details and history. */
@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class OrderReader {

    private final OrderRepository repository;

    public OrderReader(OrderRepository repository) {
        this.repository = repository;
    }

    public Detail replay(long customerId, String key, String canonical) {
        StoredOrder order = repository.byKey(customerId, key);
        if (!order.canonical().equals(canonical)) {
            throw new ApiException(
                    409, "IDEMPOTENCY_CONFLICT", "Key already used with different items");
        }
        return repository.detail(order.id(), customerId);
    }

    public Detail detail(long id, long customerId) {
        return repository.detail(id, customerId);
    }

    public History history(long customerId, int page, int size) {
        return repository.history(customerId, page, size);
    }
}

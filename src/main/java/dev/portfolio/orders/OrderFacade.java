package dev.portfolio.orders;

import dev.portfolio.orders.Models.OrderRequest;
import dev.portfolio.orders.Models.Placement;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;

/** Calls the writer proxy and recovers only the customer/key duplicate after rollback. */
@Service
public class OrderFacade {

    private final OrderWriter writer;

    private final OrderReader reader;

    public OrderFacade(OrderWriter writer, OrderReader reader) {
        this.writer = writer;
        this.reader = reader;
    }

    public Placement place(long customerId, String key, OrderRequest request) {
        try {
            // The proxy commits or rolls back before this call returns or throws.
            return new Placement(writer.place(customerId, key, request), true);
        } catch (DuplicateKeyException e) {
            if (!customerKeyConflict(e)) {
                throw e;
            }
            return new Placement(reader.replay(customerId, key, request.canonical()), false);
        }
    }

    static boolean customerKeyConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && sql.getErrorCode() == 1062
                    && sql.getMessage() != null
                    && sql.getMessage()
                            .matches(
                                    "(?s).*for key"
                                        + " ['`](?:[^'`]*\\.)?uk_orders_customer_key['`].*")) {
                return true;
            }
        }
        return false;
    }
}

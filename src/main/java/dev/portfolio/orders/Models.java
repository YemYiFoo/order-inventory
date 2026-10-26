package dev.portfolio.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/** Immutable request and response data; money is serialized as exact decimal text. */
public final class Models {

    private Models() {}

    public record Product(
            long id, String sku, String name, String price, String currency, int availableStock) {}

    public record ItemRequest(long productId, int quantity) {}

    public record OrderRequest(List<ItemRequest> items) {

        public String canonical() {
            var text = new StringBuilder("v1");
            items.forEach(
                    i -> text.append('|').append(i.productId()).append(':').append(i.quantity()));
            return text.toString();
        }
    }

    public record OrderItem(
            long productId,
            String sku,
            String name,
            int quantity,
            String unitPrice,
            String lineTotal) {}

    public record Summary(
            long id, String status, String currency, String totalAmount, Instant createdAt) {}

    public record Detail(
            long id,
            String status,
            String currency,
            String totalAmount,
            Instant createdAt,
            List<OrderItem> items) {}

    public record History(List<Summary> items, int page, int size, long totalElements) {}

    public record Placement(Detail order, boolean created) {}

    public record StoredOrder(long id, long customerId, String canonical, String status) {}

    static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}

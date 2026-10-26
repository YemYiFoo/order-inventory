package dev.portfolio.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/** Checks pure validation and the specific duplicate-constraint classifier. */
class InputTest {

    @Test
    void strictInputAndCanonicalization() {
        assertEquals(
                "v1|101:2|102:1",
                OrderInput.parse(
                                "{\"items\":[{\"productId\":102,\"quantity\":1},{\"productId\":101,\"quantity\":2}]}"
                                        .getBytes(StandardCharsets.UTF_8))
                        .canonical());
        for (String bad :
                new String[] {
                    "{}",
                    "null",
                    "[]",
                    "{\"items\":[]}",
                    "{\"items\":[],\"items\":[]}",
                    "{\"items\":[{\"productId\":101,\"quantity\":1.1}]}",
                    "{\"items\":[{\"productId\":\"101\",\"quantity\":1}]}",
                    "{\"items\":[{\"productId\":101,\"quantity\":1}]} {}"
                }) {
            assertThrows(
                    ApiException.class,
                    () -> OrderInput.parse(bad.getBytes(StandardCharsets.UTF_8)),
                    bad);
        }
    }

    @Test
    void duplicateRecoveryIsSpecific() {
        assertTrue(
                OrderFacade.customerKeyConflict(
                        new SQLException(
                                "Duplicate entry 'x' for key 'orders.uk_orders_customer_key'",
                                "23000",
                                1062)));
        assertFalse(
                OrderFacade.customerKeyConflict(
                        new SQLException(
                                "Duplicate entry 'x' for key 'products.PRIMARY'", "23000", 1062)));
        assertFalse(
                OrderFacade.customerKeyConflict(
                        new SQLException("constraint uk_orders_customer_key", "23000", 1452)));
    }
}

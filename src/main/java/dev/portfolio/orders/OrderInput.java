package dev.portfolio.orders;

import dev.portfolio.orders.Models.ItemRequest;
import dev.portfolio.orders.Models.OrderRequest;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates bounded JSON, headers and identifiers before business writes. */
public final class OrderInput {

    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    private OrderInput() {}

    public static String key(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1 || !values.getFirst().matches("[A-Za-z0-9._:-]{1,128}")) {
            throw ApiException.invalid("One valid Idempotency-Key is required");
        }
        return values.getFirst();
    }

    public static OrderRequest read(HttpServletRequest request) throws IOException {
        // Content-Encoding is a list-valued field; a later field must not bypass validation.
        for (String field : Collections.list(request.getHeaders("Content-Encoding"))) {
            for (String encoding : field.split(",", -1)) {
                String token = encoding.trim();
                // RFC 9110 list recipients ignore empty elements; Tomcat bounds header size.
                if (!token.isEmpty() && !token.equalsIgnoreCase("identity")) {
                    throw new ApiException(
                            415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Content-Encoding");
                }
            }
        }
        String type = request.getContentType();
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json");
        }
        byte[] bytes = request.getInputStream().readNBytes(65_537);
        if (bytes.length > 65_536) {
            throw new ApiException(413, "BODY_TOO_LARGE", "Maximum body size is 65536 bytes");
        }
        return parse(bytes);
    }

    public static OrderRequest parse(byte[] bytes) {
        try {
            JsonNode root = JSON.readTree(bytes);
            fields(root, Set.of("items"));
            JsonNode array = root.get("items");
            if (array == null || !array.isArray() || array.isEmpty() || array.size() > 20) {
                throw ApiException.invalid("Supply 1 to 20 items");
            }
            var items = new ArrayList<ItemRequest>();
            var ids = new HashSet<Long>();
            for (var item : array) {
                fields(item, Set.of("productId", "quantity"));
                JsonNode productId = item.get("productId");
                JsonNode quantity = item.get("quantity");
                if (productId == null
                        || !productId.isIntegralNumber()
                        || !productId.canConvertToLong()
                        || productId.longValue() <= 0
                        || quantity == null
                        || !quantity.isIntegralNumber()
                        || !quantity.canConvertToInt()
                        || quantity.intValue() < 1
                        || quantity.intValue() > 1000) {
                    throw ApiException.invalid("IDs and quantities must be integers in range");
                }
                if (!ids.add(productId.longValue())) {
                    throw ApiException.invalid("Duplicate product");
                }
                items.add(new ItemRequest(productId.longValue(), quantity.intValue()));
            }
            items.sort(Comparator.comparingLong(ItemRequest::productId));
            return new OrderRequest(List.copyOf(items));
        } catch (JacksonException e) {
            throw ApiException.invalid("Invalid JSON");
        }
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject() || node.size() != allowed.size()) {
            throw ApiException.invalid("Invalid object fields");
        }
        for (String field : node.propertyNames()) {
            if (!allowed.contains(field)) {
                throw ApiException.invalid("Unknown field");
            }
        }
    }

    public static long id(String value) {
        try {
            if (!value.matches("[0-9]+")) {
                throw ApiException.invalid("Invalid ID");
            }
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw ApiException.invalid("Invalid ID");
            }
            return id;
        } catch (NumberFormatException e) {
            throw ApiException.invalid("Invalid ID");
        }
    }

    public static void emptyBody(HttpServletRequest request) throws IOException {
        if (request.getInputStream().read() != -1) {
            throw ApiException.invalid("This operation has no body");
        }
    }
}

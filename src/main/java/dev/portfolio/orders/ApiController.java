package dev.portfolio.orders;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Maps HTTP requests to validated business operations and responses. */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final OrderRepository repository;

    private final OrderFacade facade;

    private final OrderReader reader;

    private final OrderWriter writer;

    public ApiController(
            OrderRepository repository,
            OrderFacade facade,
            OrderReader reader,
            OrderWriter writer) {
        this.repository = repository;
        this.facade = facade;
        this.reader = reader;
        this.writer = writer;
    }

    @GetMapping("/products")
    Object products() {
        return Map.of("items", repository.products());
    }

    @GetMapping("/products/{id}")
    Object product(@PathVariable String id) {
        return repository.product(OrderInput.id(id));
    }

    @PostMapping(value = "/orders", produces = "application/json")
    ResponseEntity<?> place(
            @AuthenticationPrincipal SecurityConfig.Account user, HttpServletRequest http)
            throws IOException {
        String key = OrderInput.key(http);
        var result = facade.place(user.id(), key, OrderInput.read(http));
        return ResponseEntity.status(result.created() ? 201 : 200)
                .location(URI.create("/api/orders/" + result.order().id()))
                .body(result.order());
    }

    @GetMapping("/orders/{id}")
    Object detail(@AuthenticationPrincipal SecurityConfig.Account user, @PathVariable String id) {
        return reader.detail(OrderInput.id(id), user.id());
    }

    @GetMapping("/orders")
    Object history(
            @AuthenticationPrincipal SecurityConfig.Account user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw ApiException.invalid("Invalid page or size");
        }
        return reader.history(user.id(), page, size);
    }

    @PostMapping(value = "/orders/{id}/cancel", produces = "application/json")
    Object cancel(
            @AuthenticationPrincipal SecurityConfig.Account user,
            @PathVariable String id,
            HttpServletRequest http)
            throws IOException {
        OrderInput.emptyBody(http);
        return writer.transition(OrderInput.id(id), user.id(), "CANCELLED");
    }

    @PostMapping(value = "/admin/orders/{id}/ship", produces = "application/json")
    Object ship(@PathVariable String id, HttpServletRequest http) throws IOException {
        OrderInput.emptyBody(http);
        return writer.transition(OrderInput.id(id), null, "SHIPPED");
    }
}

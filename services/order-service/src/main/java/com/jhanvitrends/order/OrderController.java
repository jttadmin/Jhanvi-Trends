package com.jhanvitrends.order;

import com.jhanvitrends.order.Dtos.CreateOrderRequest;
import com.jhanvitrends.order.Dtos.OrderResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@Valid @RequestBody CreateOrderRequest request) {
        return orders.placeOrder(request);
    }

    @GetMapping("/orders")
    public List<OrderResponse> list(@RequestParam String userId) {
        return orders.forUser(userId);
    }

    @GetMapping("/orders/{id}")
    public OrderResponse get(@PathVariable String id) {
        return orders.byId(id);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid order: " + e.getBindingResult().getFieldErrors()
                .stream().map(f -> f.getField() + " " + f.getDefaultMessage()).toList()));
    }
}

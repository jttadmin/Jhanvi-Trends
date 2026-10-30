package com.jhanvitrends.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request/response and event payloads. */
public final class Dtos {

    private Dtos() {
    }

    public record LineItem(
            @Min(1) int productId,
            @NotBlank String name,
            @NotNull @DecimalMin("0.01") BigDecimal unitPrice,
            @Min(1) @Max(50) int quantity) {
    }

    public record CreateOrderRequest(
            @NotBlank String userId,
            String userEmail,
            @NotNull Map<String, Object> shippingAddress,
            @NotEmpty List<@Valid LineItem> items) {
    }

    public record OrderResponse(
            String id, String userId, String status, BigDecimal totalAmount, String currency,
            Map<String, Object> shippingAddress, String paymentId, Instant createdAt, List<LineItem> items) {

        static OrderResponse from(OrderEntity o) {
            return new OrderResponse(o.getId().toString(), o.getUserId(), o.getStatus(), o.getTotalAmount(),
                    o.getCurrency(), o.getShippingAddress(), o.getPaymentId(), o.getCreatedAt(),
                    o.getItems().stream()
                            .map(i -> new LineItem(i.getProductId(), i.getName(), i.getUnitPrice(), i.getQuantity()))
                            .toList());
        }
    }

    /** Published to the order-events Kafka topic; type is order.placed or order.failed. */
    public record OrderEvent(
            String type, String orderId, String userId, String userEmail, String status, BigDecimal totalAmount,
            String currency, int itemCount, Instant occurredAt) {
    }

    public record ReservationRequest(String orderRef, List<Map<String, Integer>> items) {
    }

    public record PaymentRequest(String orderRef, String userId, BigDecimal amount, String currency) {
    }

    public record PaymentResponse(String paymentId, String status, String reason) {
    }
}

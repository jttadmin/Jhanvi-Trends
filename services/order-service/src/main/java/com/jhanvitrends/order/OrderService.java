package com.jhanvitrends.order;

import com.jhanvitrends.order.Dtos.CreateOrderRequest;
import com.jhanvitrends.order.Dtos.LineItem;
import com.jhanvitrends.order.Dtos.OrderEvent;
import com.jhanvitrends.order.Dtos.OrderResponse;
import com.jhanvitrends.order.Dtos.PaymentRequest;
import com.jhanvitrends.order.Dtos.PaymentResponse;
import com.jhanvitrends.order.Dtos.ReservationRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Checkout saga: reserve stock -> charge payment -> persist -> commit stock -> publish event.
 * Any failure after the reservation releases the stock again (compensation).
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository repository;
    private final RestClient inventory;
    private final RestClient payment;
    private final KafkaTemplate<String, Object> kafka;
    private final String topic;
    private final String currency;
    private final MeterRegistry meters;
    private final Counter placed;
    private final DistributionSummary orderValue;

    public OrderService(OrderRepository repository,
                        @Qualifier("inventoryClient") RestClient inventory,
                        @Qualifier("paymentClient") RestClient payment,
                        KafkaTemplate<String, Object> kafka,
                        @Value("${jhanvitrends.order-events-topic}") String topic,
                        @Value("${jhanvitrends.currency}") String currency,
                        MeterRegistry meters) {
        this.repository = repository;
        this.inventory = inventory;
        this.payment = payment;
        this.kafka = kafka;
        this.topic = topic;
        this.currency = currency;
        this.meters = meters;
        this.placed = Counter.builder("orders.placed").description("Orders confirmed").register(meters);
        this.orderValue = DistributionSummary.builder("orders.value").description("Confirmed order value")
                .baseUnit("inr").serviceLevelObjectives(500, 1000, 2500, 5000, 10000, 25000, 50000).register(meters);
    }

    private void failed(String reason) {
        meters.counter("orders.failed", "reason", reason).increment();
    }

    public OrderResponse placeOrder(CreateOrderRequest req) {
        UUID orderId = UUID.randomUUID();
        String ref = orderId.toString();
        BigDecimal total = req.items().stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 1. Reserve stock (all-or-nothing on the inventory side).
        try {
            List<Map<String, Integer>> lines = req.items().stream()
                    .map(i -> Map.of("productId", i.productId(), "quantity", i.quantity())).toList();
            inventory.post().uri("/reservations").body(new ReservationRequest(ref, lines)).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.Conflict e) {
            failed("out_of_stock");
            log.warn("order rejected, out of stock orderId={} userId={}", ref, req.userId());
            throw new ApiException(HttpStatus.CONFLICT, "Sorry, an item in your cart just went out of stock");
        } catch (RestClientException e) {
            failed("inventory_unavailable");
            log.error("inventory-service call failed orderId={}", ref, e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory is unavailable, please retry");
        }

        OrderEntity order = new OrderEntity(orderId, req.userId(), total, currency, req.shippingAddress());
        for (LineItem i : req.items()) {
            order.getItems().add(new OrderItemEntity(i.productId(), i.name(), i.unitPrice(), i.quantity()));
        }

        // 2. Charge payment; release the reservation if it does not go through.
        try {
            PaymentResponse p = payment.post().uri("/payments")
                    .body(new PaymentRequest(ref, req.userId(), total, currency))
                    .retrieve().body(PaymentResponse.class);
            order.setPaymentId(p == null ? null : p.paymentId());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() != 402) {
                release(ref);
                failed("payment_error");
                log.error("payment-service rejected request orderId={} status={}", ref, e.getStatusCode().value());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Payment could not be processed");
            }
            release(ref);
            order.setStatus(OrderEntity.Status.PAYMENT_FAILED);
            repository.save(order);
            publish("order.failed", order, req.userEmail());
            failed("payment_declined");
            log.warn("payment declined orderId={} userId={} amount={}", ref, req.userId(), total);
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "Your payment was declined");
        } catch (RestClientException e) {
            release(ref);
            failed("payment_unavailable");
            log.error("payment-service call failed orderId={}", ref, e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Payments are unavailable, please retry");
        }

        // 3. Persist, commit the stock and tell the world.
        order.setStatus(OrderEntity.Status.CONFIRMED);
        repository.save(order);
        try {
            inventory.post().uri("/reservations/{ref}/commit", ref).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("could not commit reservation, it stays RESERVED orderId={}", ref, e);
        }
        publish("order.placed", order, req.userEmail());
        placed.increment();
        orderValue.record(total.doubleValue());
        log.info("order confirmed orderId={} userId={} total={} items={}", ref, req.userId(), total, req.items().size());
        return OrderResponse.from(order);
    }

    private void release(String ref) {
        try {
            inventory.post().uri("/reservations/{ref}/release", ref).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.error("could not release reservation orderId={}", ref, e);
        }
    }

    private void publish(String type, OrderEntity o, String email) {
        try {
            int count = o.getItems().stream().mapToInt(OrderItemEntity::getQuantity).sum();
            OrderEvent event = new OrderEvent(type, o.getId().toString(), o.getUserId(), email, o.getStatus(),
                    o.getTotalAmount(), o.getCurrency(), count, Instant.now());
            kafka.send(topic, event.orderId(), event).whenComplete((result, err) -> {
                if (err != null) {
                    log.error("failed to publish {} orderId={}", type, event.orderId(), err);
                    meters.counter("orders.events.publish.failed").increment();
                } else {
                    meters.counter("orders.events.published", "type", type).increment();
                }
            });
        } catch (Exception e) {
            // The order is already saved; a lost notification must not fail checkout.
            log.error("failed to publish {} orderId={}", type, o.getId(), e);
            meters.counter("orders.events.publish.failed").increment();
        }
    }

    public List<OrderResponse> forUser(String userId) {
        return repository.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream().map(OrderResponse::from).toList();
    }

    public OrderResponse byId(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "order not found");
        }
        return repository.findById(uuid).map(OrderResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "order not found"));
    }
}

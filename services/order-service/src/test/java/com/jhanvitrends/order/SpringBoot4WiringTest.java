package com.jhanvitrends.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.hibernate.type.format.jackson.JacksonIntegration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.web.client.RestClient;

/**
 * Guards the parts of the app that Spring Boot 4 wires differently from Boot 3 and that would otherwise
 * only fail at runtime in the cluster: modular auto-configuration (Kafka, RestClient), the Jackson 3 Kafka
 * serializer, and Hibernate's JSON mapping. Needs no database or Kafka broker.
 */
class SpringBoot4WiringTest {

    /** Loads the real application.yaml with the auto-configurations OrderService depends on. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, KafkaAutoConfiguration.class, RestClientAutoConfiguration.class));

    @Test
    void kafkaAndRestClientAreAutoConfiguredFromApplicationYaml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RestClient.Builder.class);
            assertThat(context).hasSingleBean(KafkaTemplate.class);

            DefaultKafkaProducerFactory<?, ?> producers = context.getBean(DefaultKafkaProducerFactory.class);
            Object valueSerializer = producers.getConfigurationProperties().get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
            assertThat(valueSerializer).isEqualTo(JacksonJsonSerializer.class);
        });
    }

    /** notification-service (Python) reads these events, so the JSON shape is a contract. */
    @Test
    void orderEventJsonKeepsItsShape() {
        var event = new Dtos.OrderEvent("order.placed", "o-1", "u-1", "a@example.com", "CONFIRMED",
                new BigDecimal("1499.50"), "INR", 2, Instant.parse("2026-11-05T00:00:00Z"));

        try (var serializer = new JacksonJsonSerializer<Object>()) {
            serializer.configure(Map.of(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, false), false);
            String json = new String(serializer.serialize("order-events", event), StandardCharsets.UTF_8);

            assertThat(json)
                    .contains("\"type\":\"order.placed\"")
                    .contains("\"orderId\":\"o-1\"")
                    .contains("\"totalAmount\":1499.50")
                    .contains("\"itemCount\":2")
                    .contains("\"occurredAt\":\"2026-11-05T00:00:00Z\"");
        }
    }

    /** OrderEntity.shippingAddress is a jsonb column (@JdbcTypeCode(SqlTypes.JSON)); Hibernate needs a JSON mapper. */
    @Test
    void hibernateFindsJackson3ForJsonColumns() {
        assertThat(JacksonIntegration.getJsonJackson3FormatMapperOrNull()).isNotNull();
    }
}

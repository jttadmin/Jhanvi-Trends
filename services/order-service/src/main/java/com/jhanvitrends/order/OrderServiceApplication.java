package com.jhanvitrends.order;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    /** Created on startup by Spring's KafkaAdmin. Keyed by order id, so one order's events stay ordered. */
    @Bean
    NewTopic orderEventsTopic(@Value("${jhanvitrends.order-events-topic}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    private static SimpleClientHttpRequestFactory timeouts(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectMs);
        f.setReadTimeout(readMs);
        return f;
    }

    @Bean
    RestClient inventoryClient(RestClient.Builder builder, @Value("${jhanvitrends.inventory-url}") String url) {
        return builder.clone().baseUrl(url).requestFactory(timeouts(2000, 5000)).build();
    }

    @Bean
    RestClient paymentClient(RestClient.Builder builder, @Value("${jhanvitrends.payment-url}") String url) {
        return builder.clone().baseUrl(url).requestFactory(timeouts(2000, 10000)).build();
    }
}

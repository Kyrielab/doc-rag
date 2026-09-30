package com.zzx.docrag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queue topology for async ingestion.
 *
 * <p>The main queue is bound to a dead-letter exchange: when the listener retry template
 * exhausts {@code spring.rabbitmq.listener.simple.retry.max-attempts} and the message is
 * rejected (default-requeue-rejected=false), it lands in {@code docrag.ingest.dlq} instead
 * of vanishing - a poison message stays inspectable while the consumer keeps working.
 */
@Configuration
public class RabbitConfig {

    public static final String INGEST_EXCHANGE = "docrag.ingest.ex";
    public static final String INGEST_QUEUE = "docrag.ingest";
    public static final String INGEST_ROUTING_KEY = "ingest";
    public static final String DLX_EXCHANGE = "docrag.ingest.dlx";
    public static final String DLQ = "docrag.ingest.dlq";

    @Bean
    public DirectExchange ingestExchange() {
        return new DirectExchange(INGEST_EXCHANGE, true, false);
    }

    @Bean
    public Queue ingestQueue() {
        return QueueBuilder.durable(INGEST_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(INGEST_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding ingestBinding() {
        return BindingBuilder.bind(ingestQueue()).to(ingestExchange()).with(INGEST_ROUTING_KEY);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(INGEST_ROUTING_KEY);
    }

    /** JSON messages keep the queue inspectable in the management UI. */
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter(ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(mapper);
    }
}

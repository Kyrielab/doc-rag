package com.zzx.docrag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Queue topology for async ingestion.
 *
 * <p>The main queue is bound to a dead-letter exchange: when the listener retry template
 * exhausts {@code spring.rabbitmq.listener.simple.retry.max-attempts} and the message is
 * rejected (default-requeue-rejected=false), it lands in {@code docrag.ingest.dlq} instead
 * of vanishing - a poison message stays inspectable while the consumer keeps working.
 *
 * <p>Producer side (roadmap hardening): with publisher-confirm-type=correlated the broker
 * confirms each publish asynchronously; a nack or an unroutable return flips the record to
 * FAILED, so "accepted" never silently means "lost between app and broker".
 */
@Configuration
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);
    private static final Pattern DOC_ID_IN_JSON = Pattern.compile("\"docId\"\\s*:\\s*\"([^\"]+)\"");

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

    /**
     * Registers broker confirm/return callbacks on the auto-configured template.
     * The repository is resolved lazily through ObjectProvider to keep this config class
     * free of a hard dependency on the persistence layer (and any bean cycle with it).
     */
    @Bean
    public RabbitTemplateCustomizer ingestConfirmCustomizer(ObjectProvider<DocumentRepository> repositories) {
        return template -> {
            template.setConfirmCallback((correlationData, ack, cause) -> {
                if (ack) {
                    return;
                }
                String docId = correlationData != null ? correlationData.getId() : null;
                log.warn("Broker NACK for ingest job docId={}: {}", docId, cause);
                markPublishFailed(repositories, docId, "broker nack: " + cause);
            });
            template.setReturnsCallback(returned -> {
                String body = returned.getMessage().getBody() != null
                        ? new String(returned.getMessage().getBody(), StandardCharsets.UTF_8)
                        : "";
                log.warn("Ingest message returned unroutable ({}): {}", returned.getReplyText(), body);
                Matcher m = DOC_ID_IN_JSON.matcher(body);
                markPublishFailed(repositories, m.find() ? m.group(1) : null,
                        "message returned: " + returned.getReplyText());
            });
        };
    }

    private static void markPublishFailed(ObjectProvider<DocumentRepository> repositories,
                                          String docId, String error) {
        if (docId == null) {
            return;
        }
        try {
            DocumentRepository repository = repositories.getIfAvailable();
            if (repository == null) {
                return;
            }
            repository.findById(docId).ifPresent(record -> {
                if (DocumentRecord.PENDING.equals(record.getStatus())) {
                    record.setStatus(DocumentRecord.FAILED);
                    record.setError(error);
                    record.setUpdatedAt(Instant.now());
                    repository.save(record);
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not mark docId={} FAILED after publish problem: {}", docId, e.toString());
        }
    }
}

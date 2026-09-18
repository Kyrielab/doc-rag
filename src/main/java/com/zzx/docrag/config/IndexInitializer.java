package com.zzx.docrag.config;

import com.zzx.docrag.es.ChunkGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates the Elasticsearch index at startup.
 *
 * <p>Failing to reach Elasticsearch at boot is logged rather than fatal. A developer who
 * starts the app before {@code docker compose up -d} should get a clear warning, not a
 * stack trace that hides the actual cause, and the rest of the app (document listing,
 * config endpoints) stays inspectable.
 */
@Component
@Order(1)
public class IndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexInitializer.class);

    private final ChunkGateway chunkGateway;

    public IndexInitializer(ChunkGateway chunkGateway) {
        this.chunkGateway = chunkGateway;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            chunkGateway.ensureIndex();
        } catch (RuntimeException e) {
            log.error("Elasticsearch index initialization failed: {}", e.toString());
            log.error("Infrastructure is probably not up yet. Start it with: docker compose up -d");
        }
    }
}

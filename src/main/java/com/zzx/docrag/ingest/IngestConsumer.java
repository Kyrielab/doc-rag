package com.zzx.docrag.ingest;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Queue consumer. Deliberately thin: all state transitions live in {@link DocumentService}
 * so the exact same code path serves sync API calls, redeliveries and manual replays.
 */
@Component
public class IngestConsumer {

    private final DocumentService documentService;

    public IngestConsumer(DocumentService documentService) {
        this.documentService = documentService;
    }

    @RabbitListener(queues = RabbitConfig.INGEST_QUEUE)
    public void onIngestJob(IngestJob job) {
        documentService.processJob(job.docId());
    }
}

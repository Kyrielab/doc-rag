package com.zzx.docrag.api;

import com.zzx.docrag.ingest.DocumentRecord;
import com.zzx.docrag.ingest.DocumentService;
import com.zzx.docrag.rag.RagService;
import com.zzx.docrag.retrieve.RetrievalResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer contract tests over the REAL Spring MVC + Jackson stack (@WebMvcTest slice).
 *
 * <p>Why this class exists: bug #10. A hand-rolled ObjectMapper bean shadowed Boot's
 * auto-configured mapper, and the first endpoint that serialized a java.time.Instant field
 * returned a 500. Every unit test and the context-assembly test stayed green - only a real
 * HTTP round trip through the message converters could catch it. These tests pin that gap.
 */
@WebMvcTest(controllers = {DocumentController.class, RagController.class})
class WebLayerSerializationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentService documentService;

    @MockitoBean
    private RagService ragService;

    private static DocumentRecord record(String docId) {
        DocumentRecord record = new DocumentRecord();
        record.setDocId(docId);
        record.setTitle("manual");
        record.setStatus(DocumentRecord.DONE);
        record.setChunkCount(7);
        record.setAttempts(1);
        record.setCreatedAt(Instant.parse("2026-09-30T10:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-09-30T10:05:00Z"));
        return record;
    }

    @Test
    @DisplayName("the document list serializes Instant fields as ISO-8601 (pins bug #10)")
    void documentsSerializeInstants() throws Exception {
        when(documentService.list()).thenReturn(List.of(record("d1")));

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].docId").value("d1"))
                .andExpect(jsonPath("$[0].status").value("DONE"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-09-30T10:00:00Z"));
    }

    @Test
    @DisplayName("raw submitted content never leaks through the list endpoint")
    void inlineContentIsNotSerialized() throws Exception {
        DocumentRecord record = record("d2");
        record.setInlineContent("SECRET-PAYLOAD-BODY");
        when(documentService.list()).thenReturn(List.of(record));

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("SECRET-PAYLOAD-BODY"))));
    }

    @Test
    @DisplayName("the retrieval-only search endpoint is wired (pins the experiment-19 404)")
    void searchEndpointIsWired() throws Exception {
        when(ragService.retrieveOnly("q", 3))
                .thenReturn(new RetrievalResult(List.of(), "q", 0, 0, 0, 0, 1, 2, 0, 0, ""));

        mockMvc.perform(get("/api/search").param("q", "q").param("topK", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewrittenQuery").value("q"))
                .andExpect(jsonPath("$.lexicalMillis").value(1));
    }

    @Test
    @DisplayName("a blank question is rejected with 400, not passed to the pipeline")
    void blankQuestionRejected() throws Exception {
        mockMvc.perform(post("/api/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("blank text ingestion is rejected with 400")
    void blankIngestRejected() throws Exception {
        mockMvc.perform(post("/api/documents/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}

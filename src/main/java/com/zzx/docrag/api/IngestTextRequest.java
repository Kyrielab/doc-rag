package com.zzx.docrag.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Inline text ingestion request, used for pasting notes or a README without a file upload.
 *
 * @param title   document title
 * @param content raw text
 */
public record IngestTextRequest(
        @NotBlank(message = "title must not be blank") String title,
        @NotBlank(message = "content must not be blank") String content
) {
}

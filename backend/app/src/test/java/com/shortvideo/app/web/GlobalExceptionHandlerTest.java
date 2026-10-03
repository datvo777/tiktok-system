package com.shortvideo.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.upload.domain.UploadExceptions;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class GlobalExceptionHandlerTest {

    @Test
    void objectStoreFailureIsRetryableNotAnInternalError() {
        var response = new GlobalExceptionHandler()
                .uploadStorageUnavailable(new UploadExceptions.StorageUnavailable("stat failed", new IOException("timeout")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }
}

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

    @Test
    void missingUploadObjectIsConflictWithMachineReadableCode() {
        var problem = new GlobalExceptionHandler()
                .uploadObjectMissing(new UploadExceptions.UploadObjectMissing("No object was uploaded"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getProperties()).containsEntry("code", "UPLOAD_OBJECT_MISSING");
    }

    @Test
    void outOfRangeUploadSizeIsConflictWithDistinctCode() {
        var problem = new GlobalExceptionHandler()
                .uploadSizeOutOfRange(new UploadExceptions.UploadSizeOutOfRange("Size out of range"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getProperties()).containsEntry("code", "UPLOAD_SIZE_OUT_OF_RANGE");
    }

    @Test
    void uploadsStartedFasterThanThePlatformAbsorbsAreAnswered503WithRetryAfter() {
        var response = new GlobalExceptionHandler()
                .uploadCapacityExceeded(new UploadExceptions.UploadCapacityExceeded(java.time.Duration.ofMinutes(1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
    }
}

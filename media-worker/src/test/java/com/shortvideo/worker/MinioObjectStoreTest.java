package com.shortvideo.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.worker.config.MinioConfig;
import io.minio.CopyObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinioObjectStoreTest {

    private final MinioClient client = mock(MinioClient.class);
    private final MinioConfig.MinioProperties properties = new MinioConfig.MinioProperties();
    private final MinioObjectStore store = new MinioObjectStore(client, properties, Duration.ofMillis(1));

    private static ErrorResponseException answer(String code, int httpStatus) {
        ErrorResponse error = mock(ErrorResponse.class);
        when(error.code()).thenReturn(code);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("http://localhost:9000/short-video/x").build())
                .protocol(Protocol.HTTP_1_1)
                .code(httpStatus)
                .message("status")
                .build();
        return new ErrorResponseException(error, response, null);
    }

    @Test
    void aCallThatFailsATwiceAndThenSucceedsIsNotAFailure() throws Exception {
        when(client.copyObject(any(CopyObjectArgs.class)))
                .thenThrow(new IOException("connection reset"))
                .thenThrow(new IOException("connection reset"))
                .thenReturn(null);

        store.copy("processing-temp/j/a.ts", "processed/v/1/a.ts");

        verify(client, times(3)).copyObject(any(CopyObjectArgs.class));
    }

    @Test
    void aCallThatKeepsFailingGivesUpAfterThreeTries() throws Exception {
        when(client.copyObject(any(CopyObjectArgs.class))).thenThrow(new IOException("connection refused"));

        assertThatThrownBy(() -> store.copy("a", "b")).isInstanceOf(IOException.class);

        verify(client, times(3)).copyObject(any(CopyObjectArgs.class));
    }

    @Test
    void aServerErrorIsRetriedButAClientErrorIsNot() throws Exception {
        ErrorResponseException serverError = answer("InternalError", 500);
        when(client.copyObject(any(CopyObjectArgs.class))).thenThrow(serverError).thenReturn(null);
        store.copy("a", "b");
        verify(client, times(2)).copyObject(any(CopyObjectArgs.class));

        MinioClient other = mock(MinioClient.class);
        ErrorResponseException accessDenied = answer("AccessDenied", 403);
        when(other.copyObject(any(CopyObjectArgs.class))).thenThrow(accessDenied);
        MinioObjectStore denied = new MinioObjectStore(other, properties, Duration.ofMillis(1));
        assertThatThrownBy(() -> denied.copy("a", "b")).isInstanceOf(ErrorResponseException.class);
        verify(other, times(1)).copyObject(any(CopyObjectArgs.class));
    }

    @Test
    void aMissingSourceIsATerminalFailureAndIsNotRetried(@TempDir Path dir) throws Exception {
        ErrorResponseException missing = answer("NoSuchKey", 404);
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(missing);

        assertThatThrownBy(() -> store.downloadTo("sources/x/original", dir.resolve("source")))
                .isInstanceOfSatisfying(TranscodeFailedException.class, e -> {
                    assertThat(e.failureClass()).isEqualTo("TERMINAL");
                    assertThat(e.getMessage()).contains("sources/x/original");
                });
        verify(client, times(1)).getObject(any(GetObjectArgs.class));
    }
}

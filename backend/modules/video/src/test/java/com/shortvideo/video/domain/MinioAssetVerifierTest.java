package com.shortvideo.video.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.io.IOException;
import java.util.List;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class MinioAssetVerifierTest {

    private final MinioClient client = mock(MinioClient.class);
    private final MinioAssetVerifier verifier = new MinioAssetVerifier(client, "short-video");

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
    void presentObjectsVerify() {
        assertThat(verifier.verify("processed/v/1/master.m3u8", List.of("processed/v/1/720p/index.m3u8"))).isTrue();
    }

    @Test
    void aMissingObjectIsAVerdictOfFalse() throws Exception {
        ErrorResponseException missing = answer("NoSuchKey", 404);
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(missing);

        assertThat(verifier.verify("processed/v/1/master.m3u8", List.of())).isFalse();
    }

    @Test
    void noMasterPlaylistIsAVerdictOfFalse() {
        assertThat(verifier.verify(" ", List.of())).isFalse();
    }

    @Test
    void anUnreachableStoreIsNotAVerdict() throws Exception {
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(new IOException("connection refused"));

        assertThatThrownBy(() -> verifier.verify("processed/v/1/master.m3u8", List.of()))
                .isInstanceOf(VideoExceptions.AssetStoreUnavailable.class);
    }

    @Test
    void aStoreThatAnswersWithAServerErrorIsNotAVerdict() throws Exception {
        ErrorResponseException serverError = answer("InternalError", 500);
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(serverError);

        assertThatThrownBy(() -> verifier.verify("processed/v/1/master.m3u8", List.of()))
                .isInstanceOf(VideoExceptions.AssetStoreUnavailable.class);
    }
}

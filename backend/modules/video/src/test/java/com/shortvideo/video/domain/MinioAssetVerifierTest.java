package com.shortvideo.video.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;
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
        assertThat(verifier.verify("processed/v/1/master.m3u8", List.of(
                "processed/v/1/high/index.m3u8",
                "processed/v/1/medium/index.m3u8",
                "processed/v/1/low/index.m3u8"), 0)).isTrue();
    }

    @Test
    void aMissingObjectIsAVerdictOfFalse() throws Exception {
        ErrorResponseException missing = answer("NoSuchKey", 404);
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(missing);

        assertThat(verifier.verify("processed/v/1/master.m3u8", List.of(), 0)).isFalse();
    }

    @Test
    void noMasterPlaylistIsAVerdictOfFalse() {
        assertThat(verifier.verify(" ", List.of(), 0)).isFalse();
    }

    @Test
    void anUnreachableStoreIsNotAVerdict() throws Exception {
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(new IOException("connection refused"));

        assertThatThrownBy(() -> verifier.verify("processed/v/1/master.m3u8", List.of(), 0))
                .isInstanceOf(VideoExceptions.AssetStoreUnavailable.class);
    }

    @Test
    void aStoreThatAnswersWithAServerErrorIsNotAVerdict() throws Exception {
        ErrorResponseException serverError = answer("InternalError", 500);
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(serverError);

        assertThatThrownBy(() -> verifier.verify("processed/v/1/master.m3u8", List.of(), 0))
                .isInstanceOf(VideoExceptions.AssetStoreUnavailable.class);
    }

    private void listing(String... keys) throws Exception {
        List<Result<Item>> results = new java.util.ArrayList<>();
        for (String key : keys) {
            Item item = mock(Item.class);
            when(item.objectName()).thenReturn(key);
            Result<Item> result = mock(Result.class);
            when(result.get()).thenReturn(item);
            results.add(result);
        }
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(results);
    }

    @Test
    void aVideoWithAllItsSegmentsVerifies() throws Exception {
        listing("processed/v/1/high/segment_000.ts", "processed/v/1/high/segment_001.ts",
                "processed/v/1/low/segment_000.ts", "processed/v/1/master.m3u8");

        assertThat(verifier.verify("processed/v/1/master.m3u8", List.of("processed/v/1/high/index.m3u8"), 3)).isTrue();
    }

    @Test
    void aMissingSegmentIsAVerdictOfFalseEvenThoughEveryPlaylistIsThere() throws Exception {
        listing("processed/v/1/high/segment_000.ts", "processed/v/1/low/segment_000.ts");

        assertThat(verifier.verify("processed/v/1/master.m3u8", List.of("processed/v/1/high/index.m3u8"), 3)).isFalse();
    }

    @Test
    void theSegmentsAreCountedUnderTheVersionOnly() throws Exception {
        org.mockito.ArgumentCaptor<ListObjectsArgs> args = org.mockito.ArgumentCaptor.forClass(ListObjectsArgs.class);
        listing("processed/v/1/high/segment_000.ts");

        verifier.verify("processed/v/1/master.m3u8", List.of(), 1);

        org.mockito.Mockito.verify(client).listObjects(args.capture());
        assertThat(args.getValue().prefix()).isEqualTo("processed/v/1/");
    }

    @Test
    void aStoreThatCannotBeListedIsNotAVerdict() throws Exception {
        when(client.listObjects(any(ListObjectsArgs.class))).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> verifier.verify("processed/v/1/master.m3u8", List.of(), 3))
                .isInstanceOf(VideoExceptions.AssetStoreUnavailable.class);
    }
}

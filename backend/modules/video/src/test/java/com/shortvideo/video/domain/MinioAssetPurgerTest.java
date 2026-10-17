package com.shortvideo.video.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.messages.DeleteError;
import io.minio.messages.Item;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A purge that did not happen must not look like one that did. */
class MinioAssetPurgerTest {

    private final MinioClient minio = mock(MinioClient.class);
    private final MinioAssetPurger purger = new MinioAssetPurger(minio, "bucket");

    @SuppressWarnings("unchecked")
    private Result<Item> listed(String name) throws Exception {
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn(name);
        Result<Item> result = mock(Result.class);
        when(result.get()).thenReturn(item);
        return result;
    }

    @Test
    void anEmptyPrefixIsNotAnError() {
        when(minio.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of());

        assertThatCode(() -> purger.purgePrefix("sources/x/")).doesNotThrowAnyException();
    }

    @Test
    void aFailedListingIsReportedNotSwallowed() throws Exception {
        @SuppressWarnings("unchecked")
        Result<Item> broken = mock(Result.class);
        when(broken.get()).thenThrow(new java.io.IOException("minio down"));
        when(minio.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(broken));

        assertThatThrownBy(() -> purger.purgePrefix("sources/x/"))
                .isInstanceOf(MinioAssetPurger.AssetPurgeException.class);
    }

    @Test
    void anObjectThatCouldNotBeDeletedIsReportedNotSwallowed() throws Exception {
        Result<Item> object = listed("sources/x/original");
        when(minio.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(object));
        DeleteError deleteError = mock(DeleteError.class);
        when(deleteError.objectName()).thenReturn("sources/x/original");
        when(deleteError.message()).thenReturn("AccessDenied");
        @SuppressWarnings("unchecked")
        Result<DeleteError> failure = mock(Result.class);
        when(failure.get()).thenReturn(deleteError);
        when(minio.removeObjects(any(RemoveObjectsArgs.class))).thenReturn(List.of(failure));

        assertThatThrownBy(() -> purger.purgePrefix("sources/x/"))
                .isInstanceOf(MinioAssetPurger.AssetPurgeException.class)
                .hasMessageContaining("AccessDenied");
    }

    @Test
    void aCleanDeleteReturnsNormally() throws Exception {
        Result<Item> object = listed("sources/x/original");
        when(minio.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(object));
        when(minio.removeObjects(any(RemoveObjectsArgs.class))).thenReturn(List.of());

        assertThatCode(() -> purger.purgePrefix("sources/x/")).doesNotThrowAnyException();
    }
}

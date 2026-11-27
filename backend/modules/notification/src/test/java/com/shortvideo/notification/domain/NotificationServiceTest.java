package com.shortvideo.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Existence non-disclosure: a caller marking someone else's notification as
 * read must see exactly what they would see for a notification id that does
 * not exist at all.
 */
class NotificationServiceTest {

    @Test
    void nonRecipientAndMissingNotificationProduceTheIdenticalNotFoundResponse() {
        NotificationJpaRepository repository = mock(NotificationJpaRepository.class);
        NotificationService service = new NotificationService(repository, null);

        UUID existingNotificationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        String otherCaller = UUID.randomUUID().toString();
        when(repository.findById(existingNotificationId))
                .thenReturn(Optional.of(
                        new NotificationEntity(existingNotificationId, recipientId, "LIKE", "msg", null)));

        UUID missingNotificationId = UUID.randomUUID();
        when(repository.findById(missingNotificationId)).thenReturn(Optional.empty());

        Throwable nonRecipient = catchThrowable(
                () -> service.markRead(existingNotificationId.toString(), otherCaller));
        Throwable notFound = catchThrowable(
                () -> service.markRead(missingNotificationId.toString(), otherCaller));

        assertThat(nonRecipient).isInstanceOf(NotificationExceptions.NotificationNotFound.class);
        assertThat(notFound).isInstanceOf(NotificationExceptions.NotificationNotFound.class);
        assertThat(nonRecipient.getMessage()).isEqualTo(notFound.getMessage());
    }
}

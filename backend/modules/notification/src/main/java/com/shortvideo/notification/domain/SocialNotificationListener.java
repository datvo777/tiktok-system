package com.shortvideo.notification.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.shared.events.EnvelopeCodec;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.Topics;
import com.shortvideo.shared.inbox.InboxGuard;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Notifies a creator of new comments and followers (brief section 20, Milestone 7). */
@Component
class SocialNotificationListener {

    private static final String CONSUMER = "notification-social-listener";
    private static final Logger log = LoggerFactory.getLogger(SocialNotificationListener.class);

    private final InboxGuard inbox;
    private final NotificationService notificationService;
    private final AccountDirectory accountDirectory;
    private final ObjectMapper objectMapper;

    SocialNotificationListener(
            InboxGuard inbox, NotificationService notificationService, AccountDirectory accountDirectory, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.notificationService = notificationService;
        this.accountDirectory = accountDirectory;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.SOCIAL_EVENTS, groupId = "notification-social-listener")
    @Transactional
    public void onSocialEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            Map<String, Object> p = envelope.payload();
            switch (envelope.eventType()) {
                case EventTypes.SOCIAL_VIDEO_COMMENTED -> {
                    String commenterId = (String) p.get("commenterId");
                    String videoOwnerId = (String) p.get("videoOwnerId");
                    if (!commenterId.equals(videoOwnerId)) { // no self-notification for commenting on your own video
                        notificationService.create(
                                videoOwnerId,
                                "NEW_COMMENT",
                                displayName(commenterId) + " commented on your video.",
                                (String) p.get("videoId"));
                    }
                }
                case EventTypes.SOCIAL_CREATOR_FOLLOWED -> {
                    String followerName = displayName((String) p.get("followerId"));
                    notificationService.create(
                            (String) p.get("followeeId"), "NEW_FOLLOWER", followerName + " followed you.", null);
                }
                default -> { /* not relevant to notifications */ }
            }
        } catch (Exception e) {
            log.warn("Failed to apply social event to notifications; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }

    private String displayName(String accountId) {
        return accountDirectory.find(accountId).map(a -> a.displayName()).orElse("Someone");
    }
}

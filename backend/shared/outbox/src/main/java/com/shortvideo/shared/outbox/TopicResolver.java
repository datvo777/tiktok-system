package com.shortvideo.shared.outbox;

import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.Topics;
import org.springframework.stereotype.Component;

/** Maps an outbox record to its domain topic (brief section 11). */
@Component
public class TopicResolver {

    public String topicFor(OutboxRecord record) {
        // A transcode command is still an ordinary VIDEO-aggregate outbox event
        // (same version bump, same relay), it just ships to the worker's topic
        // instead of video.events.v1.
        if (EventTypes.MEDIA_JOB_DISPATCHED.equals(record.eventType())) {
            return Topics.MEDIA_JOBS;
        }
        return switch (record.aggregateType()) {
            case AggregateTypes.VIDEO,
                 AggregateTypes.UPLOAD,
                 AggregateTypes.MODERATION,
                 AggregateTypes.APPEAL,
                 AggregateTypes.PUBLICATION -> Topics.VIDEO_EVENTS;
            case AggregateTypes.ACCOUNT -> Topics.ACCOUNT_EVENTS;
            case AggregateTypes.SOCIAL -> Topics.SOCIAL_EVENTS;
            case AggregateTypes.NOTIFICATION -> Topics.NOTIFICATION_EVENTS;
            default -> throw new IllegalArgumentException(
                    "No topic mapped for aggregate type: " + record.aggregateType());
        };
    }
}

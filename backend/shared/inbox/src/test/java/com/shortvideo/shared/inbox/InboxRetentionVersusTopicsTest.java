package com.shortvideo.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;

/**
 * The inbox is what makes a redelivered event harmless, so its rows must outlive the Kafka records
 * they dedupe: if a row is pruned while the broker can still redeliver that offset, the event is
 * applied a second time. The two are set in different places (a Java default here, topic creation in
 * the infrastructure scripts), so nothing else would notice one being changed without the other.
 */
class InboxRetentionVersusTopicsTest {

    private static final Pattern RETENTION = Pattern.compile("--config\\s+retention\\.ms=(\\d+)");

    private static Path repositoryRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("infrastructure/docker-compose.yml"))) {
                return dir;
            }
        }
        throw new IllegalStateException("infrastructure/docker-compose.yml not found above " + Path.of("").toAbsolutePath());
    }

    /** The commands that create the domain topics (three partitions; the DLTs have one). */
    private static List<String> domainTopicCreations(String script) {
        // A shell line continued with a backslash is one command.
        String joined = script.replaceAll("\\\\\\r?\\n\\s*", " ");
        return joined.lines().filter(l -> l.contains("--create") && l.contains("--partitions 3")).toList();
    }

    private static void assertEveryDomainTopicKeepsRecordsShorterThanTheInbox(String relativePath) throws IOException {
        Duration inbox = DurationStyle.detectAndParse(InboxCleanupJob.DEFAULT_RETENTION);
        List<String> creations = domainTopicCreations(Files.readString(repositoryRoot().resolve(relativePath)));

        assertThat(creations).as("domain topic creation in %s", relativePath).isNotEmpty();
        for (String creation : creations) {
            Matcher retention = RETENTION.matcher(creation);
            assertThat(retention.find())
                    .as("%s must state retention.ms for the domain topics, not leave it to the broker default: %s",
                            relativePath, creation)
                    .isTrue();
            Duration topic = Duration.ofMillis(Long.parseLong(retention.group(1)));
            assertThat(topic)
                    .as("topic retention in %s must stay shorter than the inbox's %s", relativePath, inbox)
                    .isLessThan(inbox);
        }
    }

    @Test
    void theComposeStackKeepsDomainTopicsShorterThanTheInbox() throws IOException {
        assertEveryDomainTopicKeepsRecordsShorterThanTheInbox("infrastructure/docker-compose.yml");
    }

    @Test
    void theAuthenticatedStackKeepsDomainTopicsShorterThanTheInbox() throws IOException {
        assertEveryDomainTopicKeepsRecordsShorterThanTheInbox("infrastructure/kafka/secure/init-topics-and-acls.sh");
    }

    @Test
    void aTopicRetainedLongerThanTheInboxWouldBeCaught() {
        String script = "kafka-topics.sh --create --topic \"$t\" --partitions 3 --replication-factor 1 \\\n"
                + "  --config retention.ms=2592000000\n";

        Matcher retention = RETENTION.matcher(domainTopicCreations(script).get(0));

        assertThat(retention.find()).isTrue();
        assertThat(Duration.ofMillis(Long.parseLong(retention.group(1))))
                .isGreaterThan(DurationStyle.detectAndParse(InboxCleanupJob.DEFAULT_RETENTION));
    }
}

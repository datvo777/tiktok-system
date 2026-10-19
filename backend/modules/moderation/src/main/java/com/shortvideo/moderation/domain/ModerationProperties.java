package com.shortvideo.moderation.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortvideo.moderation")
public class ModerationProperties {

    /**
     * Master switch for the automated pre-screen. Off sends every upload to the
     * human queue, which is exactly the behaviour before it existed — so turning
     * this off is always a safe response to the screener misbehaving.
     */
    private boolean autoApproveEnabled = true;

    /** Human approvals a creator needs before their uploads bypass the queue. */
    private int trustedAfterApprovals = 5;

    /**
     * Which uploads reach the human queue straight away. {@code ALL} is the behaviour before intent
     * gating, so it is both the default and the way back: records still being held are queued by the
     * held-review sweep once the owner has asked to publish them, and are otherwise left alone.
     */
    private QueueMode queueMode = QueueMode.ALL;

    public QueueMode getQueueMode() { return queueMode; }
    public void setQueueMode(QueueMode queueMode) { this.queueMode = queueMode; }
    public boolean isAutoApproveEnabled() { return autoApproveEnabled; }
    public void setAutoApproveEnabled(boolean autoApproveEnabled) { this.autoApproveEnabled = autoApproveEnabled; }
    public int getTrustedAfterApprovals() { return trustedAfterApprovals; }
    public void setTrustedAfterApprovals(int trustedAfterApprovals) { this.trustedAfterApprovals = trustedAfterApprovals; }
}

package com.shortvideo.shared.events;

/** Aggregate type discriminators used in the envelope and the outbox table. */
public final class AggregateTypes {

    public static final String VIDEO = "VIDEO";
    public static final String ACCOUNT = "ACCOUNT";
    public static final String UPLOAD = "UPLOAD";
    public static final String MODERATION = "MODERATION";
    public static final String APPEAL = "APPEAL";
    public static final String PUBLICATION = "PUBLICATION";
    public static final String SOCIAL = "SOCIAL";
    public static final String NOTIFICATION = "NOTIFICATION";

    private AggregateTypes() {}
}

package com.shortvideo.shared.audit;

/**
 * One administrative act, as the acting module describes it.
 *
 * @param actorAccountId the admin who performed it — never the affected user
 * @param action         what was done, from {@link AuditActions}
 * @param targetType     from {@link AuditTargets}
 * @param targetId       the video or account acted upon
 * @param policyCategory the moderation policy, where one applies; null otherwise
 * @param reason         free-text elaboration, where one was given
 */
public record AdminAction(
        String actorAccountId,
        String action,
        String targetType,
        String targetId,
        String policyCategory,
        String reason) {

    public static AdminAction of(String actorAccountId, String action, String targetType, String targetId) {
        return new AdminAction(actorAccountId, action, targetType, targetId, null, null);
    }

    public static AdminAction of(
            String actorAccountId, String action, String targetType, String targetId, String reason) {
        return new AdminAction(actorAccountId, action, targetType, targetId, null, reason);
    }
}

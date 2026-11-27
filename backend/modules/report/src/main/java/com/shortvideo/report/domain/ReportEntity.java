package com.shortvideo.report.domain;

import com.shortvideo.report.api.ReportReason;
import com.shortvideo.report.api.ReportResolution;
import com.shortvideo.report.api.ReportState;
import com.shortvideo.report.api.ReportSubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per report, not per subject: five people reporting the same video are
 * five separate statements, and how many there are is the signal a moderator
 * triages on. Deduplication is per reporter — one open report each — enforced by
 * a partial unique index rather than by a read-then-write here, so two
 * simultaneous taps cannot both find "no existing report" and both insert.
 */
@Entity
@Table(name = "report", schema = "report")
public class ReportEntity {

    @Id
    @Column(name = "report_id", nullable = false, updatable = false)
    private UUID reportId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20, updatable = false)
    private ReportSubjectType subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 40, updatable = false)
    private ReportReason reason;

    @Column(name = "detail", length = 1000, updatable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private ReportState state;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 30)
    private ReportResolution resolution;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Optimistic concurrency and the aggregate version carried by events (Rule 10). */
    @Version
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReportEntity() {}

    public ReportEntity(
            UUID reportId,
            ReportSubjectType subjectType,
            UUID subjectId,
            UUID reporterId,
            ReportReason reason,
            String detail) {
        Instant now = Instant.now();
        this.reportId = reportId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.reporterId = reporterId;
        this.reason = reason;
        this.detail = detail;
        this.state = ReportState.OPEN;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * @return false when the report was already closed, so a repeated resolve is
     *     an idempotent no-op rather than a second audit entry.
     */
    public boolean resolve(ReportResolution outcome, String note, UUID moderatorId) {
        if (this.state == ReportState.RESOLVED) {
            return false;
        }
        this.state = ReportState.RESOLVED;
        this.resolution = outcome;
        this.resolutionNote = note;
        this.resolvedBy = moderatorId;
        this.resolvedAt = Instant.now();
        this.updatedAt = this.resolvedAt;
        return true;
    }

    public UUID getReportId() { return reportId; }
    public ReportSubjectType getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public UUID getReporterId() { return reporterId; }
    public ReportReason getReason() { return reason; }
    public String getDetail() { return detail; }
    public ReportState getState() { return state; }
    public UUID getResolvedBy() { return resolvedBy; }
    public ReportResolution getResolution() { return resolution; }
    public String getResolutionNote() { return resolutionNote; }
    public Instant getResolvedAt() { return resolvedAt; }
    public long getAggregateVersion() { return aggregateVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

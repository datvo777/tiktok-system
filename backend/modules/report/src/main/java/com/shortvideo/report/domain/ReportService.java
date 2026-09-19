package com.shortvideo.report.domain;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.account.api.AccountView;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import com.shortvideo.report.api.ReportReason;
import com.shortvideo.report.api.ReportResolution;
import com.shortvideo.report.api.ReportState;
import com.shortvideo.report.api.ReportSubjectType;
import com.shortvideo.shared.audit.AdminAction;
import com.shortvideo.shared.audit.AdminActionRecorder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Viewer-submitted reports, feeding the same human queue the moderation console
 * already runs.
 *
 * <p>Moderation was previously admin-initiated only: it reviewed every upload on
 * the way in, and had quarantine and removal tools for afterwards, but nothing
 * connected the people who actually encounter a problem to the people who can
 * act on it. On a platform where anyone can publish to everyone, that is the
 * gap this closes.
 *
 * <p><b>Not an enforcement path.</b> Filing a report changes nothing about the
 * video's eligibility. Reports are evidence a moderator reads, and only a
 * moderation decision moves a video — otherwise a handful of coordinated
 * accounts could take down anything they liked.
 */
@Service
public class ReportService {

    /** Ceiling on one moderator queue page. */
    private static final int MAX_QUEUE_SIZE = 200;

    private final ReportJpaRepository repository;
    private final EligibilityDirectory eligibilityDirectory;
    private final AccountDirectory accountDirectory;
    private final AdminActionRecorder auditRecorder;

    public ReportService(
            ReportJpaRepository repository,
            EligibilityDirectory eligibilityDirectory,
            AccountDirectory accountDirectory,
            AdminActionRecorder auditRecorder) {
        this.repository = repository;
        this.eligibilityDirectory = eligibilityDirectory;
        this.accountDirectory = accountDirectory;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Files a report.
     *
     * <p>Idempotent per reporter and subject: a second report from the same
     * person while the first is still open returns that first report rather than
     * adding a row. Enforced by the partial unique index rather than by checking
     * first, so two simultaneous taps cannot both see "nothing here yet".
     */
    @Transactional
    public ReportView submit(
            ReportSubjectType subjectType,
            String subjectId,
            String reporterId,
            ReportReason reason,
            String detail) {

        requireReportableSubject(subjectType, subjectId, reporterId);

        UUID subject = UUID.fromString(subjectId);
        UUID reporter = UUID.fromString(reporterId);

        ReportEntity entity =
                new ReportEntity(UUID.randomUUID(), subjectType, subject, reporter, reason, blankToNull(detail));
        try {
            ReportEntity saved = repository.saveAndFlush(entity);
            return ReportView.from(saved, countOpenFor(subjectType, subject));
        } catch (DataIntegrityViolationException duplicate) {
            // The partial unique index fired: this person already has an open
            // report on this subject. That is the state they asked for.
            return repository
                    .findByStateOrderByCreatedAtAsc(ReportState.OPEN, PageRequest.of(0, MAX_QUEUE_SIZE))
                    .stream()
                    .filter(r -> r.getSubjectId().equals(subject)
                            && r.getSubjectType() == subjectType
                            && r.getReporterId().equals(reporter))
                    .findFirst()
                    .map(r -> ReportView.from(r, countOpenFor(subjectType, subject)))
                    .orElseThrow(() -> duplicate);
        }
    }

    /**
     * The moderator queue: open reports, oldest first, each carrying how many
     * other people reported the same subject.
     */
    @Transactional(readOnly = true)
    public List<ReportView> openQueue(int limit) {
        int size = Math.min(limit <= 0 ? 50 : limit, MAX_QUEUE_SIZE);
        List<ReportEntity> open =
                repository.findByStateOrderByCreatedAtAsc(ReportState.OPEN, PageRequest.of(0, size));
        if (open.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> counts = openCountsFor(open.stream().map(ReportEntity::getSubjectId).distinct().toList());
        return open.stream()
                .map(r -> ReportView.from(r, counts.getOrDefault(r.getSubjectId(), 1L)))
                .toList();
    }

    /**
     * Closes a report. Recording the outcome is deliberately separate from
     * acting on the video: a moderator dismissing a report and a moderator
     * removing a video are two different decisions, and collapsing them would
     * make the audit trail unable to tell them apart.
     */
    @Transactional
    public ReportView resolve(
            String reportId, ReportResolution outcome, String note, String moderatorAccountId) {
        ReportEntity entity = repository
                .findById(UUID.fromString(reportId))
                .orElseThrow(() -> new ReportExceptions.ReportNotFound("No such report"));

        if (entity.resolve(outcome, blankToNull(note), UUID.fromString(moderatorAccountId))) {
            auditRecorder.record(AdminAction.of(
                    moderatorAccountId,
                    "report." + outcome.name().toLowerCase(java.util.Locale.ROOT),
                    entity.getSubjectType().name().toLowerCase(java.util.Locale.ROOT),
                    entity.getSubjectId().toString(),
                    note));
        }
        ReportEntity saved = repository.saveAndFlush(entity);
        return ReportView.from(saved, countOpenFor(saved.getSubjectType(), saved.getSubjectId()));
    }

    /**
     * A report has to name something that exists, and something other than the
     * reporter's own content — reporting yourself is a mistake, not a signal.
     *
     * <p>Comments are not validated against a store here: the social module owns
     * them and this module deliberately does not depend on it. A report naming a
     * comment that has since been deleted is still useful evidence, and the
     * moderator reading the queue is the one who resolves it either way.
     */
    private void requireReportableSubject(ReportSubjectType subjectType, String subjectId, String reporterId) {
        switch (subjectType) {
            case VIDEO -> {
                VideoEligibilityView video = eligibilityDirectory
                        .findVideoEligibility(subjectId)
                        .orElseThrow(() -> new ReportExceptions.SubjectNotFound("No such video"));
                if (reporterId.equals(video.creatorId())) {
                    throw new ReportExceptions.CannotReportSelf("You cannot report your own video");
                }
            }
            case ACCOUNT -> {
                if (reporterId.equals(subjectId)) {
                    throw new ReportExceptions.CannotReportSelf("You cannot report your own account");
                }
                accountDirectory
                        .find(subjectId)
                        .filter(AccountView::isEligible)
                        .orElseThrow(() -> new ReportExceptions.SubjectNotFound("No such account"));
            }
            case COMMENT -> {
                // Nothing to validate here; see the method comment.
            }
        }
    }

    private long countOpenFor(ReportSubjectType subjectType, UUID subjectId) {
        return openCountsFor(List.of(subjectId)).getOrDefault(subjectId, 1L);
    }

    private Map<UUID, Long> openCountsFor(List<UUID> subjectIds) {
        Map<UUID, Long> counts = new HashMap<>();
        for (ReportJpaRepository.SubjectCount row : repository.countOpenBySubject(ReportState.OPEN, subjectIds)) {
            counts.put(row.getSubjectId(), row.getTotal());
        }
        return counts;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

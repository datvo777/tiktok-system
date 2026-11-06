package com.shortvideo.report.domain;

import com.shortvideo.report.api.ReportState;
import com.shortvideo.report.api.ReportSubjectType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReportJpaRepository extends JpaRepository<ReportEntity, UUID> {

    List<ReportEntity> findByStateOrderByCreatedAtAsc(ReportState state, Pageable pageable);

    Optional<ReportEntity> findBySubjectTypeAndSubjectIdAndReporterIdAndState(
            ReportSubjectType subjectType, UUID subjectId, UUID reporterId, ReportState state);

    /**
     * Inserts an OPEN report unless this reporter already has one open on the subject.
     * {@code ON CONFLICT DO NOTHING} rather than catching the unique-index violation:
     * on PostgreSQL a failed statement aborts the whole transaction (25P02), so
     * recovering with another query in the same transaction cannot work.
     *
     * @return 1 when inserted, 0 when an open report already exists.
     */
    @Modifying
    @Query(value = """
            INSERT INTO report.report (report_id, subject_type, subject_id, reporter_id, reason, detail,
                                       state, aggregate_version, created_at, updated_at)
            VALUES (:reportId, :subjectType, :subjectId, :reporterId, :reason, CAST(:detail AS VARCHAR),
                    'OPEN', 0, :now, :now)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertOpenIfAbsent(
            @Param("reportId") UUID reportId,
            @Param("subjectType") String subjectType,
            @Param("subjectId") UUID subjectId,
            @Param("reporterId") UUID reporterId,
            @Param("reason") String reason,
            @Param("detail") String detail,
            @Param("now") Instant now);

    boolean existsBySubjectTypeAndSubjectIdAndReporterIdAndState(
            ReportSubjectType subjectType, UUID subjectId, UUID reporterId, ReportState state);

    /**
     * How many distinct open reports each subject in the queue has attracted.
     *
     * <p>One grouped query for the whole page rather than a count per row: the
     * moderator queue shows twenty subjects, and a per-row count is the same
     * N+1 the feed was rewritten to avoid.
     */
    @Query("""
            SELECT r.subjectType AS subjectType, r.subjectId AS subjectId, count(r) AS total
            FROM ReportEntity r
            WHERE r.state = :state AND r.subjectId IN :subjectIds
            GROUP BY r.subjectType, r.subjectId
            """)
    List<SubjectCount> countOpenBySubject(
            @Param("state") ReportState state, @Param("subjectIds") List<UUID> subjectIds);

    /** Projection for {@link #countOpenBySubject}. */
    interface SubjectCount {
        ReportSubjectType getSubjectType();

        UUID getSubjectId();

        long getTotal();
    }
}

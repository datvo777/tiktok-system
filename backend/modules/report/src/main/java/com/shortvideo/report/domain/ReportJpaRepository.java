package com.shortvideo.report.domain;

import com.shortvideo.report.api.ReportState;
import com.shortvideo.report.api.ReportSubjectType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReportJpaRepository extends JpaRepository<ReportEntity, UUID> {

    List<ReportEntity> findByStateOrderByCreatedAtAsc(ReportState state, Pageable pageable);

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

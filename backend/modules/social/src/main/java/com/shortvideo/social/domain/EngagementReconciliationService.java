package com.shortvideo.social.domain;

import com.shortvideo.social.api.EngagementReconciler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EngagementReconciliationService implements EngagementReconciler {

    private final SocialRepository repository;

    public EngagementReconciliationService(SocialRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Sweep reconcile(String afterVideoId, int limit, Duration quietFor) {
        List<String> page = repository.engagementPage(afterVideoId, limit);
        if (page.isEmpty()) {
            return new Sweep(null, 0, 0);
        }
        int corrected = repository.reconcileEngagement(page, Instant.now().minus(quietFor));
        // A short page is the end of the table; the caller starts over from the top.
        String last = page.size() < limit ? null : page.get(page.size() - 1);
        return new Sweep(last, page.size(), corrected);
    }
}

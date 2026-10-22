package com.shortvideo.account.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private by convention: only the account module uses it. */
interface AccountJpaRepository extends JpaRepository<AccountEntity, UUID> {

    Optional<AccountEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<AccountEntity> findByHandleLower(String handleLower);

    boolean existsByHandleLower(String handleLower);

    /** Batch form of {@link #findByHandleLower}, for resolving several @mentions in one comment. */
    List<AccountEntity> findAllByHandleLowerIn(Collection<String> handleLowers);

    /** Admin search (brief section 18-adjacent admin surface): partial, case-insensitive email match. */
    List<AccountEntity> findByEmailContainingIgnoreCaseOrderByCreatedAtDesc(String emailFragment, Pageable pageable);

    /** First page of a keyset-paginated full scan, for {@link AccountService#allAccountIds}. */
    List<AccountEntity> findAllByOrderByAccountIdAsc(Pageable pageable);

    /** Next page of that same scan, continuing after the previous page's last id. */
    List<AccountEntity> findByAccountIdGreaterThanOrderByAccountIdAsc(UUID accountId, Pageable pageable);
}

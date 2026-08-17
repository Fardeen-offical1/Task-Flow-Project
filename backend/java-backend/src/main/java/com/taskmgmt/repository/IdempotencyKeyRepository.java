package com.taskmgmt.repository;

import com.taskmgmt.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    /**
     * Atomically "claims" a key: INSERT ... ON CONFLICT DO NOTHING means
     * the unique PK on idempotency_key does the race-safety for us — if
     * two requests with the same key arrive concurrently, only one INSERT
     * wins. Returns 1 if this call claimed it (first time seeing this
     * key), 0 if someone already claimed it (a retry).
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (idempotency_key, user_id)
            VALUES (:key, :userId)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int tryClaim(@Param("key") String key, @Param("userId") UUID userId);
}

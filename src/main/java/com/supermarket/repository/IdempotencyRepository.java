package com.supermarket.repository;

import java.util.Optional;

public interface IdempotencyRepository {
    record Record(String key, String username, String payloadFingerprint, String receiptJson) {}

    Optional<Record> findByKey(String key);
    void save(String key, String username, String payloadFingerprint, String receiptJson);
    void purgeOlderThanDays(int days);
}

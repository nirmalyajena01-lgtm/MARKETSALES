package com.supermarket.repository;

import com.supermarket.domain.PriceHistory;

import java.sql.Connection;
import java.util.List;

/**
 * Repository interface for manager price modification audit logs.
 */
public interface PriceHistoryRepository {
    void logPriceChange(Connection conn, PriceHistory history);
    void logPriceChange(PriceHistory history);
    List<PriceHistory> findByItemCode(String code);
    List<PriceHistory> findByItemCode(Connection conn, String code);
    List<PriceHistory> findAll();
    List<PriceHistory> findAll(Connection conn);
}

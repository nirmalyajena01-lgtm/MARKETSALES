package com.supermarket.repository;

import com.supermarket.domain.AnalyticsSummary;
import com.supermarket.domain.SalesStatistic;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository interface for sales statistics and profitability analytics.
 */
public interface AnalyticsRepository {
    List<SalesStatistic> getItemStatistics(LocalDateTime start, LocalDateTime end);
    List<SalesStatistic> getItemStatistics(Connection conn, LocalDateTime start, LocalDateTime end);
    AnalyticsSummary getSummary(LocalDateTime start, LocalDateTime end);
    AnalyticsSummary getSummary(Connection conn, LocalDateTime start, LocalDateTime end);
}

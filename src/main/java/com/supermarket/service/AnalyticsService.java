package com.supermarket.service;

import com.supermarket.domain.AnalyticsReport;
import com.supermarket.domain.AnalyticsSummary;
import com.supermarket.domain.SalesStatistic;
import com.supermarket.exception.ValidationException;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.repository.AnalyticsRepository;
import com.supermarket.repository.SqliteAnalyticsRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/**
 * Service providing period-based sales profit analytics and KPI aggregations
 * with snapshot price invariance.
 */
public class AnalyticsService {
    private final AnalyticsRepository analyticsRepository;

    public AnalyticsService(AnalyticsRepository analyticsRepository) {
        this.analyticsRepository = Objects.requireNonNull(analyticsRepository, "AnalyticsRepository cannot be null");
    }

    public AnalyticsService(DatabaseManager db) {
        this(new SqliteAnalyticsRepository(Objects.requireNonNull(db, "DatabaseManager cannot be null")));
    }

    public AnalyticsService() {
        this(DatabaseManager.getInstance());
    }

    /**
     * Generates sales and profit analytics for an arbitrary date range (inclusive).
     */
    public AnalyticsReport generateReport(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new ValidationException("Start date and end date cannot be null");
        }
        if (startDate.isAfter(endDate)) {
            throw new ValidationException(String.format("Start date (%s) cannot be after end date (%s)", startDate, endDate));
        }

        LocalDateTime startTimestamp = startDate.atStartOfDay();
        LocalDateTime endTimestamp = endDate.atTime(LocalTime.MAX);

        List<SalesStatistic> itemStats = analyticsRepository.getItemStatistics(startTimestamp, endTimestamp);
        AnalyticsSummary summary = analyticsRepository.getSummary(startTimestamp, endTimestamp);

        return new AnalyticsReport(startDate, endDate, summary, itemStats);
    }

    /**
     * Generates sales and profit analytics for a single calendar day.
     */
    public AnalyticsReport generateDailyReport(LocalDate date) {
        if (date == null) {
            throw new ValidationException("Date cannot be null");
        }
        return generateReport(date, date);
    }
}

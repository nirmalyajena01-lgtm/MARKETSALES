package com.supermarket.repository;

import com.supermarket.domain.ReceiptLineItem;
import com.supermarket.domain.SalesTransaction;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for sales billing transactions and itemized line records.
 */
public interface SalesTransactionRepository {
    void createTransaction(Connection conn, SalesTransaction tx, List<ReceiptLineItem> items);
    Optional<SalesTransaction> findBySerialNumber(String serialNumber);
    Optional<SalesTransaction> findBySerialNumber(Connection conn, String serialNumber);
    Optional<SalesTransaction> findById(long id);
    Optional<SalesTransaction> findById(Connection conn, long id);
    List<SalesTransaction> findByDateRange(LocalDateTime start, LocalDateTime end);
    List<SalesTransaction> findByDateRange(Connection conn, LocalDateTime start, LocalDateTime end);
    List<ReceiptLineItem> findItemsByTransactionId(long transactionId);
    List<ReceiptLineItem> findItemsByTransactionId(Connection conn, long transactionId);
}

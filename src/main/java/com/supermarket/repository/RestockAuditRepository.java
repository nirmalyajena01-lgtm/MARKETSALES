package com.supermarket.repository;

import com.supermarket.domain.RestockAudit;

import java.sql.Connection;
import java.util.List;

/**
 * Repository interface for employee restock audit logs.
 */
public interface RestockAuditRepository {
    void logRestock(Connection conn, RestockAudit audit);
    void logRestock(RestockAudit audit);
    List<RestockAudit> findAll();
    List<RestockAudit> findAll(Connection conn);
    List<RestockAudit> findByItemCode(String code);
    List<RestockAudit> findByItemCode(Connection conn, String code);
}

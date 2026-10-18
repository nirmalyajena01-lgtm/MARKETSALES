package com.supermarket.repository;

import com.supermarket.domain.Item;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing supermarket catalog items.
 */
public interface ItemRepository {
    Optional<Item> findByCode(String code);
    Optional<Item> findByCode(Connection conn, String code);
    Optional<Item> findById(long id);
    Optional<Item> findById(Connection conn, long id);
    List<Item> findAll();
    List<Item> findAll(Connection conn);
    List<Item> search(String query, String category);
    List<Item> search(Connection conn, String query, String category);
    List<Item> findLowStock();
    List<Item> findLowStock(Connection conn);
    void save(Item item);
    void save(Connection conn, Item item);
    boolean decrementStock(Connection conn, String code, int quantity);
    void incrementStock(Connection conn, String code, int quantity);
    void updateSellingPrice(Connection conn, String code, BigDecimal newPrice);
}

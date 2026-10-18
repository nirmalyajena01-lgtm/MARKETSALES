package com.supermarket.repository;

import com.supermarket.domain.Item;
import com.supermarket.persistence.DatabaseManager;

import com.supermarket.persistence.TimestampParser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class SqliteItemRepository implements ItemRepository {
    private final DatabaseManager db;

    public SqliteItemRepository(DatabaseManager db) {
        this.db = Objects.requireNonNull(db, "DatabaseManager cannot be null");
    }

    @Override
    public Optional<Item> findByCode(String code) {
        try (Connection conn = db.getConnection()) {
            return findByCode(conn, code);
        } catch (SQLException e) {
            throw new RuntimeException("Error finding item by code: " + code, e);
        }
    }

    @Override
    public Optional<Item> findByCode(Connection conn, String code) {
        String sql = "SELECT id, code, name, category, cost_price, current_selling_price, current_stock, min_stock_threshold, created_at, updated_at " +
                "FROM items WHERE code = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding item by code: " + code, e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Item> findById(long id) {
        try (Connection conn = db.getConnection()) {
            return findById(conn, id);
        } catch (SQLException e) {
            throw new RuntimeException("Error finding item by id: " + id, e);
        }
    }

    @Override
    public Optional<Item> findById(Connection conn, long id) {
        String sql = "SELECT id, code, name, category, cost_price, current_selling_price, current_stock, min_stock_threshold, created_at, updated_at " +
                "FROM items WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding item by id: " + id, e);
        }
        return Optional.empty();
    }

    @Override
    public List<Item> findAll() {
        try (Connection conn = db.getConnection()) {
            return findAll(conn);
        } catch (SQLException e) {
            throw new RuntimeException("Error retrieving all items", e);
        }
    }

    @Override
    public List<Item> findAll(Connection conn) {
        String sql = "SELECT id, code, name, category, cost_price, current_selling_price, current_stock, min_stock_threshold, created_at, updated_at " +
                "FROM items ORDER BY category, name";
        List<Item> items = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                items.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error retrieving all items", e);
        }
        return items;
    }

    @Override
    public List<Item> search(String query, String category) {
        try (Connection conn = db.getConnection()) {
            return search(conn, query, category);
        } catch (SQLException e) {
            throw new RuntimeException("Error searching items", e);
        }
    }

    @Override
    public List<Item> search(Connection conn, String query, String category) {
        StringBuilder sql = new StringBuilder("SELECT id, code, name, category, cost_price, current_selling_price, current_stock, min_stock_threshold, created_at, updated_at FROM items WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (query != null && !query.isBlank()) {
            sql.append("AND (code LIKE ? OR name LIKE ?) ");
            String wildcard = "%" + query.trim() + "%";
            params.add(wildcard);
            params.add(wildcard);
        }

        if (category != null && !category.isBlank() && !category.equalsIgnoreCase("All") && !category.equalsIgnoreCase("All Categories")) {
            sql.append("AND category = ? ");
            params.add(category.trim());
        }

        sql.append("ORDER BY category, name");

        List<Item> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error searching items", e);
        }
        return items;
    }

    @Override
    public List<Item> findLowStock() {
        try (Connection conn = db.getConnection()) {
            return findLowStock(conn);
        } catch (SQLException e) {
            throw new RuntimeException("Error finding low stock items", e);
        }
    }

    @Override
    public List<Item> findLowStock(Connection conn) {
        String sql = "SELECT id, code, name, category, cost_price, current_selling_price, current_stock, min_stock_threshold, created_at, updated_at " +
                "FROM items WHERE current_stock <= min_stock_threshold ORDER BY current_stock ASC";
        List<Item> items = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                items.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding low stock items", e);
        }
        return items;
    }

    @Override
    public void save(Item item) {
        db.getWriteLock().lock();
        try (Connection conn = db.getConnection()) {
            save(conn, item);
        } catch (SQLException e) {
            throw new RuntimeException("Error saving item: " + item.code(), e);
        } finally {
            db.getWriteLock().unlock();
        }
    }

    @Override
    public void save(Connection conn, Item item) {
        Optional<Item> existing = findByCode(conn, item.code());
        if (existing.isPresent()) {
            String sql = "UPDATE items SET name = ?, category = ?, cost_price = ?, current_selling_price = ?, current_stock = ?, min_stock_threshold = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE code = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, item.name());
                ps.setString(2, item.category());
                ps.setDouble(3, item.costPrice().doubleValue());
                ps.setDouble(4, item.currentSellingPrice().doubleValue());
                ps.setInt(5, item.currentStock());
                ps.setInt(6, item.minStockThreshold());
                ps.setString(7, item.code());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Error updating item: " + item.code(), e);
            }
        } else {
            String sql = "INSERT INTO items (code, name, category, cost_price, current_selling_price, current_stock, min_stock_threshold, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, item.code());
                ps.setString(2, item.name());
                ps.setString(3, item.category());
                ps.setDouble(4, item.costPrice().doubleValue());
                ps.setDouble(5, item.currentSellingPrice().doubleValue());
                ps.setInt(6, item.currentStock());
                ps.setInt(7, item.minStockThreshold());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Error inserting item: " + item.code(), e);
            }
        }
    }

    @Override
    public boolean decrementStock(Connection conn, String code, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity to decrement must be positive: " + quantity);
        }
        String sql = "UPDATE items SET current_stock = current_stock - ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') " +
                "WHERE code = ? AND current_stock >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, quantity);
            ps.setString(2, code);
            ps.setInt(3, quantity);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Error decrementing stock for item: " + code, e);
        }
    }

    @Override
    public void incrementStock(Connection conn, String code, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity to increment must be positive: " + quantity);
        }
        String sql = "UPDATE items SET current_stock = current_stock + ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE code = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, quantity);
            ps.setString(2, code);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new IllegalArgumentException("Cannot increment stock: item not found for code: " + code);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error incrementing stock for item: " + code, e);
        }
    }

    @Override
    public void updateSellingPrice(Connection conn, String code, BigDecimal newPrice) {
        Objects.requireNonNull(newPrice, "New selling price cannot be null");
        if (newPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Selling price cannot be negative: " + newPrice);
        }
        String sql = "UPDATE items SET current_selling_price = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE code = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, newPrice.doubleValue());
            ps.setString(2, code);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new IllegalArgumentException("Cannot update selling price: item not found for code: " + code);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error updating selling price for item: " + code, e);
        }
    }

    private Item mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String code = rs.getString("code");
        String name = rs.getString("name");
        String category = rs.getString("category");
        BigDecimal costPrice = BigDecimal.valueOf(rs.getDouble("cost_price")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sellingPrice = BigDecimal.valueOf(rs.getDouble("current_selling_price")).setScale(2, RoundingMode.HALF_UP);
        int currentStock = rs.getInt("current_stock");
        int minStockThreshold = rs.getInt("min_stock_threshold");
        LocalDateTime createdAt = TimestampParser.parse(rs.getString("created_at"));
        LocalDateTime updatedAt = TimestampParser.parse(rs.getString("updated_at"));

        return new Item(id, code, name, category, costPrice, sellingPrice, currentStock, minStockThreshold, createdAt, updatedAt);
    }
}

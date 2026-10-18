package com.supermarket.persistence;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe SQLite connection and transaction manager.
 * Supports file-based and in-memory isolated databases with WAL mode,
 * foreign keys enabled, busy timeout, and write serialization lock.
 */
public final class DatabaseManager implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(DatabaseManager.class.getName());
    private static final AtomicLong MEM_DB_COUNTER = new AtomicLong(0);

    private static volatile DatabaseManager instance;

    private final String dbPath;
    private final String jdbcUrl;
    private final SQLiteDataSource dataSource;
    private final ReentrantLock writeLock = new ReentrantLock(true);
    private final boolean isInMemory;
    private Connection anchorConnection;

    @FunctionalInterface
    public interface TransactionWork {
        void execute(Connection conn) throws SQLException;
    }

    @FunctionalInterface
    public interface TransactionResultWork<T> {
        T execute(Connection conn) throws SQLException;
    }

    public DatabaseManager(String path) {
        Objects.requireNonNull(path, "Database path cannot be null");
        this.dbPath = path;
        this.isInMemory = path.equals(":memory:") || path.contains("mode=memory") || path.startsWith("file:mem");

        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);

        if (this.isInMemory) {
            // Use shared in-memory URI so multiple connections access the same in-memory DB
            String memName = "memdb_" + System.nanoTime() + "_" + MEM_DB_COUNTER.incrementAndGet();
            this.jdbcUrl = "jdbc:sqlite:file:" + memName + "?mode=memory&cache=shared";
            config.setJournalMode(SQLiteConfig.JournalMode.MEMORY);
        } else {
            File dbFile = new File(path);
            if (dbFile.getParentFile() != null && !dbFile.getParentFile().exists()) {
                dbFile.getParentFile().mkdirs();
            }
            this.jdbcUrl = "jdbc:sqlite:" + path;
            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        }

        this.dataSource = new SQLiteDataSource(config);
        this.dataSource.setUrl(this.jdbcUrl);

        if (this.isInMemory) {
            try {
                // Keep an anchor connection alive for the lifetime of DatabaseManager
                this.anchorConnection = DriverManager.getConnection(this.jdbcUrl, config.toProperties());
                try (Statement stmt = this.anchorConnection.createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = ON;");
                }
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "Failed to initialize in-memory SQLite anchor connection", e);
                throw new IllegalStateException("Failed to initialize in-memory SQLite anchor connection", e);
            }
        }
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    instance = new DatabaseManager(getDefaultDatabasePath());
                }
            }
        }
        return instance;
    }

    public static synchronized DatabaseManager initialize(String dbPath) {
        if (instance != null) {
            instance.close();
        }
        instance = new DatabaseManager(dbPath);
        return instance;
    }

    public static DatabaseManager createInMemory() {
        return new DatabaseManager(":memory:");
    }

    public static DatabaseManager forDatabase(String dbPath) {
        return new DatabaseManager(dbPath);
    }

    public static String getDefaultDatabasePath() {
        String userHome = System.getProperty("user.home");
        File appDir = new File(userHome, ".supermarket_automation");
        if (!appDir.exists()) {
            appDir.mkdirs();
        }
        return new File(appDir, "supermarket.db").getAbsolutePath();
    }

    public Connection getConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            if (!isInMemory) {
                stmt.execute("PRAGMA busy_timeout = 5000;");
            }
        }
        return conn;
    }

    public ReentrantLock getWriteLock() {
        return writeLock;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public String getDbPath() {
        return dbPath;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public boolean isInMemory() {
        return isInMemory;
    }

    /**
     * Executes work within a serialized write transaction.
     */
    public void executeInTransaction(TransactionWork work) throws SQLException {
        writeLock.lock();
        try (Connection conn = getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                work.execute(conn);
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Executes work returning a result within a serialized write transaction.
     */
    public <T> T executeInTransactionWithResult(TransactionResultWork<T> work) throws SQLException {
        writeLock.lock();
        try (Connection conn = getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                T result = work.execute(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close() {
        if (anchorConnection != null) {
            try {
                if (!anchorConnection.isClosed()) {
                    anchorConnection.close();
                }
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "Error closing anchor connection", e);
            }
            anchorConnection = null;
        }
    }
}

package org.cubexmc.booklite.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.cubexmc.booklite.BookLitePlugin;
import org.cubexmc.booklite.config.ConfigManager;
import org.cubexmc.booklite.model.BookRecord;

public class BookRepository {

    private static final Gson GSON = new Gson();

    private final BookLitePlugin plugin;
    private final ConfigManager config;
    private Connection connection;

    public BookRepository(BookLitePlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public synchronized void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Cannot create BookLite data folder.");
            }
            File db = new File(plugin.getDataFolder(), config.getSqliteFile());
            connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA busy_timeout = 5000");
                if (config.isWal()) {
                    st.execute("PRAGMA journal_mode = WAL");
                    st.execute("PRAGMA synchronous = NORMAL");
                }
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA user_version = 1");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS books (
                          id TEXT PRIMARY KEY,
                          content_hash TEXT NOT NULL,
                          title_raw TEXT NOT NULL,
                          author TEXT NOT NULL,
                          pages_json TEXT NOT NULL,
                          total_pages INTEGER NOT NULL,
                          created_at INTEGER NOT NULL,
                          updated_at INTEGER NOT NULL,
                          deleted_at INTEGER NULL
                        )
                        """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_books_hash ON books(content_hash)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_books_deleted ON books(deleted_at)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_books_created ON books(created_at)");
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize BookLite SQLite storage.", ex);
            throw new IllegalStateException(ex);
        }
    }

    public synchronized void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to close BookLite database.", ex);
        }
    }

    public synchronized BookRecord saveOrGet(BookRecord draft) throws SQLException {
        BookRecord existing = findActiveByHash(draft.contentHash());
        if (existing != null) return existing;

        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO books
                  (id, content_hash, title_raw, author, pages_json, total_pages, created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, draft.id());
            ps.setString(2, draft.contentHash());
            ps.setString(3, draft.title());
            ps.setString(4, draft.author());
            ps.setString(5, GSON.toJson(draft.pages()));
            ps.setInt(6, draft.totalPages());
            ps.setLong(7, draft.createdAt());
            ps.setLong(8, draft.updatedAt());
            if (draft.deletedAt() == null) {
                ps.setNull(9, Types.BIGINT);
            } else {
                ps.setLong(9, draft.deletedAt());
            }
            ps.executeUpdate();
        }
        return draft;
    }

    public synchronized BookRecord find(String id) throws SQLException {
        if (id == null || id.isBlank()) return null;
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM books WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    public synchronized List<BookRecord> findByPrefix(String prefix, int limit) throws SQLException {
        List<BookRecord> out = new ArrayList<>();
        if (prefix == null || prefix.isBlank()) return out;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM books WHERE id LIKE ? ORDER BY created_at DESC LIMIT ?")) {
            ps.setString(1, prefix + "%");
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        }
        return out;
    }

    public synchronized List<BookRecord> list(int offset, int limit) throws SQLException {
        List<BookRecord> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM books ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, Math.max(1, limit));
            ps.setInt(2, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        }
        return out;
    }

    public synchronized boolean softDelete(String id, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE books SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL")) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized boolean undelete(String id, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE books SET deleted_at = NULL, updated_at = ? WHERE id = ? AND deleted_at IS NOT NULL")) {
            ps.setLong(1, now);
            ps.setString(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized int purgeDeleted(long deletedBeforeOrAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM books WHERE deleted_at IS NOT NULL AND deleted_at <= ?")) {
            ps.setLong(1, deletedBeforeOrAt);
            return ps.executeUpdate();
        }
    }

    public synchronized Stats stats() throws SQLException {
        int total = count("SELECT COUNT(*) FROM books");
        int active = count("SELECT COUNT(*) FROM books WHERE deleted_at IS NULL");
        int deleted = count("SELECT COUNT(*) FROM books WHERE deleted_at IS NOT NULL");
        return new Stats(total, active, deleted);
    }

    private BookRecord findActiveByHash(String hash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM books WHERE content_hash = ? AND deleted_at IS NULL ORDER BY created_at ASC LIMIT 1")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    private int count(String sql) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private BookRecord read(ResultSet rs) throws SQLException {
        List<String> pages = GSON.fromJson(rs.getString("pages_json"),
                new TypeToken<List<String>>() {}.getType());
        long deleted = rs.getLong("deleted_at");
        Long deletedAt = rs.wasNull() ? null : deleted;
        return new BookRecord(
                rs.getString("id"),
                rs.getString("content_hash"),
                rs.getString("title_raw"),
                rs.getString("author"),
                pages == null ? List.of("") : pages,
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                deletedAt
        );
    }

    public record Stats(int total, int active, int deleted) {}
}

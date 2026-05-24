package org.cubexmc.booklite.service;

import java.sql.SQLException;
import java.util.List;

import org.cubexmc.booklite.BookLitePlugin;
import org.cubexmc.booklite.model.BookRecord;
import org.cubexmc.booklite.storage.BookRepository;

public class BookService {

    private final BookLitePlugin plugin;
    private final BookRepository repository;
    private final BookCache cache;

    public BookService(BookLitePlugin plugin, BookRepository repository, BookCache cache) {
        this.plugin = plugin;
        this.repository = repository;
        this.cache = cache;
    }

    public BookRecord saveOrGet(BookRecord draft) throws SQLException {
        BookRecord record = repository.saveOrGet(draft);
        cache.put(record);
        return record;
    }

    public BookRecord find(String id) throws SQLException {
        BookRecord cached = cache.get(id);
        if (cached != null) return cached;
        BookRecord record = repository.find(id);
        if (record != null) cache.put(record);
        return record;
    }

    public Resolution resolve(String input) throws SQLException {
        if (input == null || input.isBlank()) return Resolution.notFound();
        BookRecord exact = find(input);
        if (exact != null) return Resolution.found(exact);
        var matches = repository.findByPrefix(input.toLowerCase(), 2);
        if (matches.isEmpty()) return Resolution.notFound();
        if (matches.size() > 1) return Resolution.ambiguousMatch();
        BookRecord record = matches.get(0);
        cache.put(record);
        return Resolution.found(record);
    }

    /** Outcome of resolving a (possibly short) book id: a hit, a miss, or an ambiguous prefix. */
    public record Resolution(BookRecord record, boolean ambiguous) {
        static Resolution found(BookRecord record) { return new Resolution(record, false); }
        static Resolution notFound() { return new Resolution(null, false); }
        static Resolution ambiguousMatch() { return new Resolution(null, true); }

        public boolean found() { return record != null; }
    }

    public List<BookRecord> list(int page, int perPage) throws SQLException {
        int safePage = Math.max(1, page);
        return repository.list((safePage - 1) * perPage, perPage);
    }

    public List<String> completeIds(String prefix, CompletionScope scope, int limit) throws SQLException {
        return repository.completeIdsByPrefix(prefix,
                scope == CompletionScope.ALL || scope == CompletionScope.ACTIVE,
                scope == CompletionScope.ALL || scope == CompletionScope.DELETED,
                limit);
    }

    public enum CompletionScope {
        ALL,
        ACTIVE,
        DELETED
    }

    public boolean delete(String id) throws SQLException {
        boolean changed = repository.softDelete(id, System.currentTimeMillis());
        if (changed) cache.invalidate(id);
        return changed;
    }

    public boolean undelete(String id) throws SQLException {
        boolean changed = repository.undelete(id, System.currentTimeMillis());
        if (changed) cache.invalidate(id);
        return changed;
    }

    public int purgeDeleted(long deletedBeforeOrAt) throws SQLException {
        int purged = repository.purgeDeleted(deletedBeforeOrAt);
        if (purged > 0) cache.clear();
        return purged;
    }

    public BookRepository.Stats stats() throws SQLException {
        return repository.stats();
    }

    public int cacheSize() {
        return cache.size();
    }

    public void logStorageFailure(String action, Exception ex) {
        plugin.getLogger().warning("BookLite storage action failed during " + action + ": " + ex.getMessage());
    }
}

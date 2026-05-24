package org.cubexmc.booklite.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

import org.cubexmc.booklite.BookLitePlugin;
import org.cubexmc.booklite.config.ConfigManager;
import org.cubexmc.booklite.model.BookRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BookRepositoryTest {

    @TempDir
    File dataFolder;

    private BookRepository repository;

    @BeforeEach
    void setUp() {
        BookLitePlugin plugin = mock(BookLitePlugin.class);
        lenient().when(plugin.getDataFolder()).thenReturn(dataFolder);
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("BookLiteTest"));

        ConfigManager config = mock(ConfigManager.class);
        lenient().when(config.getSqliteFile()).thenReturn("test.db");
        lenient().when(config.isWal()).thenReturn(true);

        repository = new BookRepository(plugin, config);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        if (repository != null) repository.close();
    }

    private BookRecord draft(String id, String hash) {
        long now = System.currentTimeMillis();
        return draftAt(id, hash, now);
    }

    private BookRecord draftAt(String id, String hash, long now) {
        return new BookRecord(id, hash, "Title", "Author", List.of("page-a", "page-b"),
                now, now, null);
    }

    @Test
    void saveOrGetDeduplicatesByActiveHash() throws SQLException {
        BookRecord first = repository.saveOrGet(draft("id-1", "shared-hash"));
        BookRecord second = repository.saveOrGet(draft("id-2", "shared-hash"));

        assertEquals(first.id(), second.id());
        assertEquals(1, repository.stats().total());
    }

    @Test
    void saveOrGetInsertsNewBookForDifferentHash() throws SQLException {
        repository.saveOrGet(draft("id-1", "hash-a"));
        repository.saveOrGet(draft("id-2", "hash-b"));
        assertEquals(2, repository.stats().total());
    }

    @Test
    void saveOrGetReinsertsWhenPreviousCopyWasDeleted() throws SQLException {
        repository.saveOrGet(draft("id-1", "hash-x"));
        assertTrue(repository.softDelete("id-1", 1_000L));

        BookRecord reinserted = repository.saveOrGet(draft("id-2", "hash-x"));
        assertEquals("id-2", reinserted.id());
        assertEquals(2, repository.stats().total());
        assertEquals(1, repository.stats().active());
    }

    @Test
    void findReturnsNullForBlankOrUnknownId() throws SQLException {
        assertNull(repository.find(null));
        assertNull(repository.find(""));
        assertNull(repository.find("missing"));
    }

    @Test
    void softDeleteAndUndeleteCycle() throws SQLException {
        repository.saveOrGet(draft("id-1", "hash"));

        assertTrue(repository.softDelete("id-1", 2_000L));
        assertFalse(repository.softDelete("id-1", 2_000L), "second soft-delete is a no-op");
        assertTrue(repository.find("id-1").isDeleted());

        assertTrue(repository.undelete("id-1", 3_000L));
        assertFalse(repository.undelete("id-1", 3_000L), "second undelete is a no-op");
        assertFalse(repository.find("id-1").isDeleted());
    }

    @Test
    void purgeRemovesOnlyDeletedBooksAtOrBeforeCutoff() throws SQLException {
        repository.saveOrGet(draft("id-old", "hash-old"));
        repository.saveOrGet(draft("id-new", "hash-new"));
        repository.saveOrGet(draft("id-active", "hash-active"));
        repository.softDelete("id-old", 1_000L);
        repository.softDelete("id-new", 5_000L);

        assertEquals(0, repository.purgeDeleted(500L), "cutoff before any deletion");
        assertEquals(1, repository.purgeDeleted(1_000L), "only id-old is at/before cutoff");
        assertNull(repository.find("id-old"));
        assertNotNull(repository.find("id-new"));
        assertNotNull(repository.find("id-active"));
    }

    @Test
    void findByPrefixMatchesAndRespectsLimit() throws SQLException {
        repository.saveOrGet(draft("aaa-1", "hash-1"));
        repository.saveOrGet(draft("aaa-2", "hash-2"));
        repository.saveOrGet(draft("bbb-1", "hash-3"));

        assertEquals(2, repository.findByPrefix("aaa", 10).size());
        assertEquals(1, repository.findByPrefix("aaa", 1).size());
        assertEquals(1, repository.findByPrefix("bbb", 10).size());
        assertTrue(repository.findByPrefix("zzz", 10).isEmpty());
        assertTrue(repository.findByPrefix(" ", 10).isEmpty());
    }

    @Test
    void completeIdsByPrefixFiltersByDeletedState() throws SQLException {
        repository.saveOrGet(draftAt("aaa-active", "hash-active", 1_000L));
        repository.saveOrGet(draftAt("aaa-deleted", "hash-deleted", 2_000L));
        repository.saveOrGet(draftAt("bbb-active", "hash-other", 3_000L));
        repository.softDelete("aaa-deleted", 1_000L);

        assertEquals(List.of("aaa-deleted", "aaa-active"),
                repository.completeIdsByPrefix("aaa", true, true, 10));
        assertEquals(List.of("aaa-active"),
                repository.completeIdsByPrefix("aaa", true, false, 10));
        assertEquals(List.of("aaa-deleted"),
                repository.completeIdsByPrefix("aaa", false, true, 10));
    }

    @Test
    void completeIdsByPrefixReturnsRecentIdsForBlankPrefix() throws SQLException {
        repository.saveOrGet(draftAt("id-1", "hash-1", 1_000L));
        repository.saveOrGet(draftAt("id-2", "hash-2", 2_000L));

        assertEquals(List.of("id-2"),
                repository.completeIdsByPrefix("", true, true, 1));
        assertTrue(repository.completeIdsByPrefix("id", false, false, 10).isEmpty());
    }

    @Test
    void statsCountsActiveAndDeletedSeparately() throws SQLException {
        repository.saveOrGet(draft("id-1", "hash-1"));
        repository.saveOrGet(draft("id-2", "hash-2"));
        repository.saveOrGet(draft("id-3", "hash-3"));
        repository.softDelete("id-2", 1_000L);

        BookRepository.Stats stats = repository.stats();
        assertEquals(3, stats.total());
        assertEquals(2, stats.active());
        assertEquals(1, stats.deleted());
    }

    @Test
    void readPreservesPagesAcrossPersistence() throws SQLException {
        repository.saveOrGet(draft("id-1", "hash"));
        BookRecord loaded = repository.find("id-1");
        assertNotNull(loaded);
        assertEquals(List.of("page-a", "page-b"), loaded.pages());
        assertEquals(2, loaded.totalPages());
    }
}

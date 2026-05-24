package org.cubexmc.booklite.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BookRecordTest {

    private BookRecord record(Long deletedAt) {
        long now = 1_000L;
        return new BookRecord("id-1", "hash", "Title", "Author",
                List.of("page"), now, now, deletedAt);
    }

    @Test
    void generatesIdWhenNullAndKeepsExplicitId() {
        BookRecord generated = new BookRecord(null, "hash", "t", "a",
                List.of("p"), 0, 0, null);
        assertNotNull(generated.id());
        assertFalse(generated.id().isBlank());

        BookRecord explicit = record(null);
        assertEquals("id-1", explicit.id());
    }

    @Test
    void shortIdTruncatesToEightChars() {
        BookRecord longId = new BookRecord("0123456789abcdef", "hash", "t", "a",
                List.of("p"), 0, 0, null);
        assertEquals("01234567", longId.shortId());

        BookRecord shortId = new BookRecord("abc", "hash", "t", "a",
                List.of("p"), 0, 0, null);
        assertEquals("abc", shortId.shortId());
    }

    @Test
    void isDeletedReflectsDeletedAt() {
        assertFalse(record(null).isDeleted());
        assertFalse(record(0L).isDeleted());
        assertTrue(record(5_000L).isDeleted());
    }

    @Test
    void pagesAreDefensivelyCopiedAndImmutable() {
        List<String> source = new ArrayList<>(List.of("a", "b"));
        BookRecord rec = new BookRecord("id", "hash", "t", "a", source, 0, 0, null);

        source.add("c");
        assertEquals(2, rec.totalPages());
        assertThrows(UnsupportedOperationException.class, () -> rec.pages().add("x"));
    }

    @Test
    void nullPagesFallBackToSingleEmptyPage() {
        BookRecord rec = new BookRecord("id", "hash", "t", "a", null, 0, 0, null);
        assertEquals(1, rec.totalPages());
        assertEquals("", rec.pages().get(0));
    }
}

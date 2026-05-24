package org.cubexmc.booklite.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.google.gson.Gson;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.cubexmc.booklite.BookLitePlugin;
import org.cubexmc.booklite.config.ConfigManager;
import org.cubexmc.booklite.lang.LanguageManager;
import org.cubexmc.booklite.model.BookRecord;

public class BookCodec {

    private static final Gson GSON = new Gson();
    private static final int TITLE_LIMIT = 32;

    private final BookLitePlugin plugin;
    private final PdcKeys keys;
    private final ConfigManager config;

    public BookCodec(BookLitePlugin plugin, PdcKeys keys, ConfigManager config) {
        this.plugin = plugin;
        this.keys = keys;
        this.config = config;
    }

    public BookRecord createRecord(BookMeta meta, String fallbackAuthor) {
        String title = meta.hasTitle() ? meta.getTitle() : "Untitled";
        String author = meta.hasAuthor() ? meta.getAuthor() : fallbackAuthor;
        List<String> pages = new ArrayList<>(meta.getPages());
        if (pages.isEmpty()) pages.add("");

        String payload = GSON.toJson(List.of(title == null ? "" : title,
                author == null ? "" : author,
                pages));
        String hash = sha256(payload);
        long now = System.currentTimeMillis();
        return new BookRecord(null, hash, title, author, pages, now, now, null);
    }

    public ValidationResult validate(BookRecord record) {
        if (record.totalPages() > config.getMaxPages()) {
            return ValidationResult.tooManyPages(record.totalPages(), config.getMaxPages());
        }

        int total = 0;
        for (String page : record.pages()) {
            int pageBytes = GSON.toJson(page == null ? "" : page).getBytes(StandardCharsets.UTF_8).length;
            if (pageBytes > config.getMaxPageJsonBytes()) {
                return ValidationResult.pageTooLarge(pageBytes, config.getMaxPageJsonBytes());
            }
            total += pageBytes;
        }
        if (total > config.getMaxTotalJsonBytes()) {
            return ValidationResult.bookTooLarge(total, config.getMaxTotalJsonBytes());
        }
        return ValidationResult.success();
    }

    public boolean isWrittenBook(ItemStack item) {
        return item != null && item.getType() == Material.WRITTEN_BOOK
                && item.getItemMeta() instanceof BookMeta;
    }

    public boolean isBookLite(ItemStack item) {
        if (!isWrittenBook(item)) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(keys.bookId(), PersistentDataType.STRING);
    }

    public String readBookId(ItemStack item) {
        if (!isWrittenBook(item)) return null;
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null
                : meta.getPersistentDataContainer().get(keys.bookId(), PersistentDataType.STRING);
    }

    public int readGeneration(ItemStack item) {
        if (!isWrittenBook(item)) return 0;
        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta == null) return 0;
        Integer stored = itemMeta.getPersistentDataContainer()
                .get(keys.generation(), PersistentDataType.INTEGER);
        if (stored != null) return Math.max(0, stored);
        return itemMeta instanceof BookMeta bookMeta ? generationToInt(bookMeta) : 0;
    }

    public ItemStack createShell(BookRecord record, int generation) {
        return createShell(record, generation, 1);
    }

    public ItemStack createShell(BookRecord record, int generation, int amount) {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK, Math.max(1, amount));
        BookMeta meta = (BookMeta) item.getItemMeta();
        applyShellMeta(meta, record, generation);
        item.setItemMeta(meta);
        return item;
    }

    public BookMeta applyShellMeta(BookMeta meta, BookRecord record, int generation) {
        meta.setTitle(visibleTitle(record));
        meta.setAuthor(visibleAuthor(record));
        meta.setPages(List.of(""));
        if (config.isPreserveGeneration()) {
            meta.setGeneration(intToGeneration(generation));
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keys.bookId(), PersistentDataType.STRING, record.id());
        pdc.set(keys.generation(), PersistentDataType.INTEGER, Math.max(0, generation));
        pdc.set(keys.version(), PersistentDataType.INTEGER, PdcKeys.SCHEMA_VERSION);
        meta.setLore(null);
        return meta;
    }

    public ItemStack createReadable(BookRecord record, int generation) {
        LanguageManager lang = plugin.languageManager();
        if (record == null) {
            return createSystemBook(lang.msg("book.missing_title"), lang.msg("book.missing_page"));
        }
        if (record.isDeleted()) {
            return createSystemBook(lang.msg("book.deleted_title"), lang.msg("book.deleted_page"));
        }
        return createFullBook(record, generation);
    }

    public ItemStack createFullBook(BookRecord record, int generation) {
        return createFullBook(record, generation, 1);
    }

    public ItemStack createFullBook(BookRecord record, int generation, int amount) {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK, Math.max(1, amount));
        BookMeta meta = (BookMeta) item.getItemMeta();
        meta.setTitle(visibleTitle(record));
        meta.setAuthor(visibleAuthor(record));
        meta.setPages(record.pages().isEmpty() ? List.of("") : record.pages());
        if (config.isPreserveGeneration()) {
            meta.setGeneration(intToGeneration(generation));
        }
        item.setItemMeta(meta);
        return item;
    }

    public int generationToInt(BookMeta meta) {
        if (meta == null || meta.getGeneration() == null) return 0;
        return switch (meta.getGeneration()) {
            case ORIGINAL -> 0;
            case COPY_OF_ORIGINAL -> 1;
            case COPY_OF_COPY -> 2;
            case TATTERED -> 3;
        };
    }

    public BookMeta.Generation intToGeneration(int generation) {
        return switch (Math.max(0, generation)) {
            case 1 -> BookMeta.Generation.COPY_OF_ORIGINAL;
            case 2 -> BookMeta.Generation.COPY_OF_COPY;
            case 3 -> BookMeta.Generation.TATTERED;
            default -> BookMeta.Generation.ORIGINAL;
        };
    }

    public boolean canCopyGeneration(int generation) {
        return generation < 2;
    }

    public int nextGeneration(int generation) {
        return Math.min(2, Math.max(0, generation) + 1);
    }

    private ItemStack createSystemBook(String title, String message) {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        meta.setTitle(truncateBookTitle(title));
        meta.setAuthor("BookLite");
        meta.setPages(List.of(message == null || message.isBlank() ? "BookLite" : message));
        item.setItemMeta(meta);
        return item;
    }

    String visibleTitle(BookRecord record) {
        return truncateBookTitle(record.title().isBlank() ? "Untitled" : record.title());
    }

    String visibleAuthor(BookRecord record) {
        return record.author().isBlank() ? "Unknown" : record.author();
    }

    private String truncateBookTitle(String title) {
        String safe = title == null || title.isBlank() ? "Untitled" : title;
        return safe.length() <= TITLE_LIMIT ? safe : safe.substring(0, TITLE_LIMIT);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            plugin.getLogger().warning("SHA-256 is unavailable; using hashCode fallback.");
            return Integer.toHexString(input.hashCode());
        }
    }

    public static final class ValidationResult {
        private final boolean ok;
        private final String key;
        private final int actual;
        private final int max;

        private ValidationResult(boolean ok, String key, int actual, int max) {
            this.ok = ok;
            this.key = key;
            this.actual = actual;
            this.max = max;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, "", 0, 0);
        }

        public static ValidationResult tooManyPages(int actual, int max) {
            return new ValidationResult(false, "book.fail_too_many_pages", actual, max);
        }

        public static ValidationResult pageTooLarge(int actual, int max) {
            return new ValidationResult(false, "book.fail_page_too_large", actual, max);
        }

        public static ValidationResult bookTooLarge(int actual, int max) {
            return new ValidationResult(false, "book.fail_book_too_large", actual, max);
        }

        public boolean ok() { return ok; }
        public String key() { return key; }
        public int actual() { return actual; }
        public int max() { return max; }
    }
}

package org.cubexmc.booklite.listener;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.cubexmc.booklite.BookLitePlugin;
import org.cubexmc.booklite.lang.LanguageManager;
import org.cubexmc.booklite.model.BookRecord;
import org.cubexmc.booklite.service.BookCodec;
import org.cubexmc.booklite.service.BookRestorer;
import org.cubexmc.booklite.service.BookService;

public class BookListener implements Listener {

    private final BookLitePlugin plugin;
    private final BookService books;
    private final BookCodec codec;
    private final BookRestorer restorer;
    private final LanguageManager lang;

    public BookListener(BookLitePlugin plugin, BookService books,
                        BookCodec codec, BookRestorer restorer, LanguageManager lang) {
        this.plugin = plugin;
        this.books = books;
        this.codec = codec;
        this.restorer = restorer;
        this.lang = lang;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(PlayerEditBookEvent event) {
        if (!event.isSigning()) return;
        if (!plugin.configManager().isAutoConvertSignedBooks()) return;
        if (plugin.configManager().isUninstallMode()) return;

        Player player = event.getPlayer();
        BookMeta originalMeta = event.getNewBookMeta();
        BookRecord draft = codec.createRecord(originalMeta, player.getName());
        BookCodec.ValidationResult validation = codec.validate(draft);
        if (!validation.ok()) {
            event.setCancelled(true);
            sendValidation(player, validation);
            return;
        }

        try {
            BookRecord record = books.saveOrGet(draft);
            int generation = codec.generationToInt(originalMeta);
            BookMeta shellMeta = (BookMeta) originalMeta.clone();
            codec.applyShellMeta(shellMeta, record, generation);
            event.setNewBookMeta(shellMeta);
            lang.send(player, "book.signed_converted", placeholders(record));
            if (plugin.configManager().isLogConversions()) {
                plugin.getLogger().info(player.getName() + " signed BookLite book " + record.id());
            }
        } catch (SQLException ex) {
            books.logStorageFailure("sign", ex);
            lang.send(player, "book.fail_storage");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRead(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        if (tryOpenLectern(event)) return;

        ItemStack item = event.getItem();
        if (!codec.isBookLite(item)) return;
        if (!event.getPlayer().hasPermission("booklite.use")) {
            lang.send(event.getPlayer(), "commands.no_permission");
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        openBookLite(event.getPlayer(), codec.readBookId(item), codec.readGeneration(item));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!plugin.configManager().isAllowCraftingCopy()) return;
        CraftingInventory inv = event.getInventory();
        ItemStack shell = null;
        int blankBooks = 0;
        boolean hasOtherIngredient = false;

        for (ItemStack item : inv.getMatrix()) {
            if (item == null || item.getType() == Material.AIR) continue;
            if (codec.isBookLite(item)) {
                if (shell != null) {
                    inv.setResult(null);
                    return;
                }
                shell = item;
            } else if (item.getType() == Material.WRITABLE_BOOK) {
                blankBooks++;
            } else {
                hasOtherIngredient = true;
            }
        }
        if (hasOtherIngredient) return;
        if (shell == null || blankBooks <= 0) return;

        int generation = codec.readGeneration(shell);
        if (!codec.canCopyGeneration(generation)) {
            inv.setResult(null);
            return;
        }

        try {
            BookRecord record = books.find(codec.readBookId(shell));
            if (record == null || record.isDeleted()) {
                inv.setResult(null);
                return;
            }
            inv.setResult(codec.createShell(record, codec.nextGeneration(generation), blankBooks));
        } catch (SQLException ex) {
            books.logStorageFailure("craft", ex);
            inv.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.configManager().isUninstallMode()) return;
        if (!plugin.configManager().isPassiveOnPlayerJoin()) return;
        restorer.restoreInventoryAsync(event.getPlayer().getInventory(),
                plugin.configManager().getMaxItemsPerTick());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!plugin.configManager().isUninstallMode()) return;
        if (!plugin.configManager().isPassiveOnInventoryOpen()) return;
        restorer.restoreInventoryAsync(event.getInventory(),
                plugin.configManager().getMaxItemsPerTick());
    }

    private boolean tryOpenLectern(PlayerInteractEvent event) {
        if (!plugin.configManager().isLecternEnabled()) return false;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return false;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.LECTERN) return false;
        if (!(block.getState() instanceof Lectern lectern)) return false;
        ItemStack book = lectern.getInventory().getItem(0);
        if (!codec.isBookLite(book)) return false;
        event.setCancelled(true);
        openBookLite(event.getPlayer(), codec.readBookId(book), codec.readGeneration(book));
        return true;
    }

    private void openBookLite(Player player, String id, int generation) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            BookRecord record = null;
            try {
                record = books.find(id);
            } catch (SQLException ex) {
                books.logStorageFailure("read", ex);
            }
            BookRecord finalRecord = record;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                player.openBook(codec.createReadable(finalRecord, generation));
                if (finalRecord == null) {
                    lang.send(player, "book.fail_missing");
                } else if (finalRecord.isDeleted()) {
                    lang.send(player, "book.fail_deleted");
                }
            });
        });
    }

    private void sendValidation(Player player, BookCodec.ValidationResult validation) {
        Map<String, String> p = new HashMap<>();
        p.put("actual", String.valueOf(validation.actual()));
        p.put("max", String.valueOf(validation.max()));
        lang.send(player, validation.key(), p);
    }

    private Map<String, String> placeholders(BookRecord record) {
        Map<String, String> p = new HashMap<>();
        p.put("id", record.id());
        p.put("short_id", record.shortId());
        p.put("title", record.title());
        p.put("author", record.author());
        return p;
    }
}

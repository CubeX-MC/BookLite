# BookLite

BookLite is a lightweight written-book storage plugin for Paper/Spigot servers.

Instead of keeping every written book's full pages inside the item stack, BookLite stores the content in a local SQLite database and leaves only a tiny PDC marker on the item. Players still sign, read, copy, and restore books through normal gameplay-style actions.

## Features

- SQLite-backed written book storage.
- Content hash dedupe: identical active books reuse the same database row.
- Tiny BookLite shell items with `book_id`, `generation`, and schema version in PDC.
- `/booklite convert` for existing vanilla written books.
- `/booklite restore` for returning a shell to a vanilla written book.
- Automatic conversion when a player signs a book.
- Right-click reading from database-backed content.
- Crafting-copy support with correct generation increment and copy-of-copy limit.
- Admin list/read/delete/undelete/status commands.
- Soft delete: deleted content opens as a friendly system book instead of leaking pages.
- Basic lectern reading compatibility: right-clicking a lectern holding a BookLite shell opens the stored book.
- Uninstall mode: async passive inventory/container restore plus `/booklite restorecontainer`.
- Short IDs from `/booklite list` are accepted by admin read/info/delete/undelete commands.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/booklite help` | any | Show available commands |
| `/booklite convert` | `booklite.convert` | Convert held vanilla written book to a BookLite shell |
| `/booklite restore` | `booklite.restore` | Restore held BookLite shell to a vanilla written book |
| `/booklite status` | `booklite.admin.status` | Show database and cache counts |
| `/booklite list [page]` | `booklite.admin.list` | List stored books |
| `/booklite info <id>` | `booklite.admin.info` | Show metadata for one stored book |
| `/booklite read <id>` | `booklite.admin.read` | Open a stored book as admin |
| `/booklite delete <id>` | `booklite.admin.delete` | Soft-delete a stored book |
| `/booklite undelete <id>` | `booklite.admin.delete` | Restore a soft-deleted book |
| `/booklite restorecontainer` | `booklite.admin.restorecontainer` | Restore BookLite shells in the opened container or targeted container block |
| `/booklite reload` | `booklite.admin.reload` | Reload config and language files |

Alias: `/bl`

## Permissions

- `booklite.use` - read BookLite books.
- `booklite.convert` - convert held vanilla books.
- `booklite.restore` - restore held BookLite books.
- `booklite.admin` - parent permission for admin commands.

## Configuration

Important sections:

```yaml
storage:
  sqlite_file: "books.db"
  wal: true

cache:
  maximum_size: 2048
  expire_after_access_minutes: 15

limits:
  max_pages: 100
  max_page_json_bytes: 8192
  max_total_json_bytes: 262144

behavior:
  auto_convert_signed_books: true
  allow_crafting_copy: true
  preserve_generation: true

uninstall:
  mode: false
```

When `uninstall.mode` is `true`, new signed books are no longer auto-converted, and BookLite will passively restore shells in player inventories and opened containers. Passive restore snapshots slots on the main thread, reads SQLite asynchronously, then replaces only if the same shell is still in the same slot. Use this before removing the plugin.

## Data Model

BookLite creates a `books` table with:

- UUID text id
- content hash
- title
- author
- pages JSON
- page count
- created/updated timestamps
- nullable soft-delete timestamp

The item shell stores only:

- `booklite:book_id`
- `booklite:generation`
- `booklite:version`

SQLite is initialized with WAL support when configured, a 5 second busy timeout, and schema `user_version = 1`.

## Current Compatibility Notes

BookLite targets the stable Bukkit/Spigot `BookMeta` API for broad compatibility. On modern Paper versions this still works, but it does not yet preserve every possible low-level 1.20.5+ data-component nuance beyond what `BookMeta` exposes.

Lectern support is read-compatible: a lectern can hold the shell, and right-clicking it opens the stored content. Redstone page-output parity is not claimed yet.

## Build

```powershell
mvn verify
```

The shaded plugin jar is created at:

```text
target/booklite-0.1.0.jar
```

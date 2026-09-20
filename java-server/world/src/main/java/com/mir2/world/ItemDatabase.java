package com.mir2.world;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Port to the standard-item catalog — the Java counterpart of M2Server's in-memory
 * {@code StdItemList} searched by name in {@code TUserEngine.CopyToUserItemFromName}
 * (UsrEngn.pas:1609).
 *
 * <p>Keeping this a port inside the world module preserves the rule that the deterministic
 * engine never touches JDBC; deployments bind it to the SQLite catalog, a data-file import
 * or the in-memory factory below.
 */
public interface ItemDatabase {

  /** Resolves one template by exact name; empty when the catalog has no such item. */
  Optional<StdItem> find(String name);

  /** Immutable name-keyed catalog; rejects duplicate names so typos fail fast at boot. */
  static ItemDatabase of(Collection<StdItem> items) {
    Map<String, StdItem> catalog = new LinkedHashMap<>();
    for (StdItem item : items) {
      if (catalog.putIfAbsent(item.name(), item) != null) {
        throw new IllegalArgumentException("duplicate item name in catalog: " + item.name());
      }
    }
    Map<String, StdItem> frozen = Map.copyOf(catalog);
    return name -> Optional.ofNullable(frozen.get(name));
  }

  static ItemDatabase of(StdItem... items) {
    return of(List.of(items));
  }

  /** Catalog without any entry, mirroring an M2Server booted without StdItems data. */
  static ItemDatabase empty() {
    return name -> Optional.empty();
  }

  /** Convenience accessor that throws instead of returning empty, for bootstrap wiring. */
  default StdItem require(String name) {
    return find(name).orElseThrow(() -> new NoSuchElementException("no such item: " + name));
  }
}

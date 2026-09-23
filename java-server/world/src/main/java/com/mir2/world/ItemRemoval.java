package com.mir2.world;

import java.util.List;
import java.util.Objects;

/**
 * One entry of {@code RM_SENDDELITEMLIST} ({@code SendDelItemList}, ObjBase.pas:22857): a
 * {@code (name, MakeIndex)} pair the client removes from its own copy of the inventory.
 *
 * <p>The name is deliberately allowed to be empty. {@code TPlayObject.DropUseItems}
 * (ObjBase.pas:15506) adds destroyed {@code Reserved and 8} gear with
 * {@code DelList.AddObject('', MakeIndex)}, so the wire really does carry {@code /MakeIndex/}
 * for those entries and the client matches on MakeIndex alone. {@link BackpackItem} cannot
 * express that, because {@link StdItem} rejects a blank name.
 */
public record ItemRemoval(String name, int makeIndex) {

  public ItemRemoval {
    Objects.requireNonNull(name, "name");
    if (makeIndex < 0) throw new IllegalArgumentException("makeIndex must not be negative");
  }

  /** The ordinary named removal used by the bag scatter and every item-destroy path. */
  public static ItemRemoval of(BackpackItem item) {
    return new ItemRemoval(item.name(), item.makeIndex());
  }

  /** {@code DelList.AddObject('', MakeIndex)} — a destroyed item, identified by index only. */
  public static ItemRemoval unnamed(BackpackItem item) {
    return new ItemRemoval("", item.makeIndex());
  }

  public static List<ItemRemoval> ofAll(List<BackpackItem> items) {
    return items.stream().map(ItemRemoval::of).toList();
  }
}

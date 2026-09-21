package com.mir2.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A player's thirteen worn items — the Java form of {@code TBaseObject.m_UseItems}
 * ({@code THumanUseItems}, Common/Grobal2.pas:797).
 *
 * <p>The record is immutable: {@link #with} and {@link #without} return new containers so the
 * world engine can stage a change and roll it back if persistence fails, exactly as the
 * existing backpack code does.
 */
public record Equipment(Map<EquipmentSlot, BackpackItem> worn) {

  public Equipment {
    Objects.requireNonNull(worn, "worn");
    EnumMap<EquipmentSlot, BackpackItem> copy = new EnumMap<>(EquipmentSlot.class);
    for (Map.Entry<EquipmentSlot, BackpackItem> entry : worn.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "slot");
      Objects.requireNonNull(entry.getValue(), "item");
      copy.put(entry.getKey(), entry.getValue());
    }
    worn = Collections.unmodifiableMap(copy);
  }

  public static Equipment empty() {
    return new Equipment(Map.of());
  }

  public Optional<BackpackItem> at(EquipmentSlot slot) {
    return Optional.ofNullable(worn.get(slot));
  }

  public boolean isEmpty() {
    return worn.isEmpty();
  }

  /** Returns a copy with {@code item} placed in {@code slot}, replacing anything already there. */
  public Equipment with(EquipmentSlot slot, BackpackItem item) {
    Objects.requireNonNull(slot, "slot");
    Objects.requireNonNull(item, "item");
    EnumMap<EquipmentSlot, BackpackItem> next = new EnumMap<>(EquipmentSlot.class);
    next.putAll(worn);
    next.put(slot, item);
    return new Equipment(next);
  }

  /** Returns a copy with {@code slot} cleared; a no-op when the slot is already empty. */
  public Equipment without(EquipmentSlot slot) {
    Objects.requireNonNull(slot, "slot");
    if (!worn.containsKey(slot)) return this;
    EnumMap<EquipmentSlot, BackpackItem> next = new EnumMap<>(EquipmentSlot.class);
    next.putAll(worn);
    next.remove(slot);
    return new Equipment(next);
  }

  /** Slot order iteration, mirroring {@code for i := Low to High(THumanUseItems)}. */
  public List<Map.Entry<EquipmentSlot, BackpackItem>> inSlotOrder() {
    List<Map.Entry<EquipmentSlot, BackpackItem>> ordered = new ArrayList<>();
    for (EquipmentSlot slot : EquipmentSlot.values()) {
      BackpackItem item = worn.get(slot);
      if (item != null) ordered.add(Map.entry(slot, item));
    }
    return List.copyOf(ordered);
  }

  /**
   * Re-derives the stat bonuses granted by the worn set, mirroring the slot loop of
   * {@code TBaseObject.RecalcAbilitys} (ObjBase.pas:2818) combined with
   * {@code TItem.ApplyItemParameters} (ItmUnit.pas:556).
   *
   * <p>The Delphi loop skips a slot when {@code wIndex <= 0} <em>or</em> {@code Dura <= 0}: a
   * fully worn-out item stays visible in its slot but stops granting stats.
   */
  public EquipmentBonus bonus() {
    EquipmentBonus.Builder builder = EquipmentBonus.builder();
    for (Map.Entry<EquipmentSlot, BackpackItem> entry : inSlotOrder()) {
      BackpackItem item = entry.getValue();
      // ObjBase.pas:2945 — broken gear contributes nothing until repaired.
      if (item.dura() <= 0) continue;
      builder.apply(entry.getKey(), item.item());
    }
    return builder.build();
  }

  /** Stable map view keyed by the numeric {@code U_*} index, for persistence layers. */
  public Map<Integer, BackpackItem> byIndex() {
    Map<Integer, BackpackItem> result = new LinkedHashMap<>();
    for (Map.Entry<EquipmentSlot, BackpackItem> entry : inSlotOrder()) {
      result.put(entry.getKey().index(), entry.getValue());
    }
    return Map.copyOf(result);
  }
}

package com.mir2.world;

/**
 * The accumulated effect of a worn set: the subset of Delphi {@code TAddAbility}
 * (Common/Grobal2.pas:1432) that the current combat model can actually consume, plus the
 * three weight totals {@code RecalcAbilitys} rebuilds from scratch on every call.
 *
 * <p>Delphi keeps AC/MAC/DC/MC/SC as {@code MakeLong(min, max)} packed dwords; this record
 * expands them into explicit bounds because the packing only matters on the wire.
 *
 * <p>Deliberately partial: the suite/special-ring flags ({@code m_boRevival},
 * {@code m_boTeleport}, the 111-217 Shape/AniCount table at ObjBase.pas:2960-3300) belong to
 * subsystems that do not exist yet and are not modelled here.
 */
public record EquipmentBonus(
    int minAc,
    int maxAc,
    int minMac,
    int maxMac,
    int minDc,
    int maxDc,
    int minMc,
    int maxMc,
    int minSc,
    int maxSc,
    int hp,
    int mp,
    int hitPoint,
    int speedPoint,
    int hitSpeed,
    int antiMagic,
    int antiPoison,
    int poisonRecover,
    int healthRecover,
    int spellRecover,
    int luck,
    int unLuck,
    int weight,
    int wearWeight,
    int handWeight,
    int maxWeightBonus,
    int maxWearWeightBonus,
    int maxHandWeightBonus) {

  public static EquipmentBonus none() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** True when nothing worn changes any stat, letting callers skip a recalculation broadcast. */
  public boolean isNeutral() {
    return equals(none());
  }

  /**
   * Accumulator translating one template at a time, following
   * {@code TItem.ApplyItemParameters} plus the {@code RecalcAbilitys} weight split.
   */
  public static final class Builder {
    private int minAc;
    private int maxAc;
    private int minMac;
    private int maxMac;
    private int minDc;
    private int maxDc;
    private int minMc;
    private int maxMc;
    private int minSc;
    private int maxSc;
    private int hp;
    private int mp;
    private int hitPoint;
    private int speedPoint;
    private int hitSpeed;
    private int antiMagic;
    private int antiPoison;
    private int poisonRecover;
    private int healthRecover;
    private int spellRecover;
    private int luck;
    private int unLuck;
    private int weight;
    private int wearWeight;
    private int handWeight;
    private int maxWeightBonus;
    private int maxWearWeightBonus;
    private int maxHandWeightBonus;

    private Builder() {}

    /**
     * Applies one worn template. {@code slot} selects the weight bucket exactly as
     * ObjBase.pas:2950-3095 does; {@code item.itemType()} selects the stat mapping.
     */
    public Builder apply(EquipmentSlot slot, StdItem item) {
      // Weight split: dress -> WearWeight, weapon/right hand -> HandWeight; every worn item
      // also adds to the overall body Weight (ObjBase.pas:3093 "Inc(m_WAbil.Weight, ...)").
      if (slot.countsTowardWearWeight()) wearWeight += item.weight();
      else if (slot.countsTowardHandWeight()) handWeight += item.weight();
      else wearWeight += item.weight();
      weight += item.weight();

      switch (item.itemType()) {
        case StdItem.ITEM_WEAPON -> {
          // AC2 = hit point, MAC2 = hit speed with a +10 bias, AC = luck, MAC = unluck.
          hitPoint += item.acMax();
          int macMax = item.macMax();
          if (macMax > 10) hitSpeed += macMax - 10;
          else hitSpeed -= macMax;
          luck += item.acMin();
          unLuck += item.macMin();
        }
        case StdItem.ITEM_ARMOR -> {
          minAc += item.acMin();
          maxAc += item.acMax();
          minMac += item.macMin();
          maxMac += item.macMax();
          // Armour reads its luck/unluck out of the signed Source byte's two halves.
          luck += item.source() & 0xff;
          unLuck += (item.source() >> 8) & 0xff;
        }
        case StdItem.ITEM_ACCESSORY -> applyAccessory(item);
        default -> {
          // ITEM_ETC: ApplyItemParameters' case statement has no branch, so nothing applies.
        }
      }

      // Outside the case statement every item type still contributes its DC/MC/SC range
      // (ItmUnit.pas:696-698), which is why armour with a DC roll still raises attack.
      minDc += item.dcMin();
      maxDc += item.dcMax();
      minMc += item.mcMin();
      maxMc += item.mcMax();
      minSc += item.scMin();
      maxSc += item.scMax();
      return this;
    }

    /** ItmUnit.pas:580-695 — the accessory branch keyed by StdMode. */
    private void applyAccessory(StdItem item) {
      switch (item.stdMode()) {
        case 19 -> {
          antiMagic += item.acMax();
          unLuck += item.macMin();
          luck += item.macMax();
        }
        case 63 -> {
          hp += item.acMin();
          mp += item.acMax();
          unLuck += item.macMin();
          luck += item.macMax();
        }
        case 20, 24 -> {
          hitPoint += item.acMax();
          speedPoint += item.macMax();
        }
        case 62 -> {
          maxHandWeightBonus += item.acMax();
          maxWeightBonus += item.macMin();
          maxWearWeightBonus += item.macMax();
        }
        case 21, 64 -> {
          healthRecover += item.acMax();
          spellRecover += item.macMax();
          hitSpeed += item.acMin();
          hitSpeed -= item.macMin();
        }
        case 23 -> {
          antiPoison += item.acMax();
          poisonRecover += item.macMax();
          hitSpeed += item.acMin();
          hitSpeed -= item.macMin();
        }
        // 52/53/54 branch on g_Config.boAddUserItemNewValue. The shipped default is the
        // "new value" path, matching the dedicated 62/63/64 handling above.
        case 53 -> {
          antiMagic += item.acMax();
          unLuck += item.macMin();
          luck += item.macMax();
        }
        case 52 -> {
          hitPoint += item.acMax();
          speedPoint += item.macMax();
        }
        case 54 -> {
          healthRecover += item.acMax();
          spellRecover += item.macMax();
          hitSpeed += item.acMin();
          hitSpeed -= item.macMin();
        }
        default -> {
          // Remaining accessory modes (15 helmet, 22 ring, 25/26 armring, 51 amulet) have no
          // branch, so only the trailing DC/MC/SC accumulation applies to them.
        }
      }
    }

    public EquipmentBonus build() {
      return new EquipmentBonus(minAc, maxAc, minMac, maxMac, minDc, maxDc, minMc, maxMc,
          minSc, maxSc, hp, mp, hitPoint, speedPoint, hitSpeed, antiMagic, antiPoison,
          poisonRecover, healthRecover, spellRecover, luck, unLuck, weight, wearWeight,
          handWeight, maxWeightBonus, maxWearWeightBonus, maxHandWeightBonus);
    }
  }
}

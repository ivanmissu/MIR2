package com.mir2.shadowdiff;

import com.mir2.protocol.ProtocolConstants;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reverse lookup of {@code SM_*} idents to their Delphi constant names, reflected once from
 * {@link ProtocolConstants} — the same trick the wiretool's FrameDescriber uses, so a new
 * constant automatically shows up in shadow reports without a hand-maintained table.
 */
final class IdentNames {
  private static final Map<Integer, String> SERVER_NAMES = collect("SM_");

  private IdentNames() {}

  static String serverName(int ident) {
    String name = SERVER_NAMES.get(ident);
    return name != null ? name : "SM_UNKNOWN(" + ident + ")";
  }

  private static Map<Integer, String> collect(String prefix) {
    Map<Integer, String> names = new TreeMap<>();
    for (Field field : ProtocolConstants.class.getFields()) {
      if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) continue;
      if (!field.getName().startsWith(prefix)) continue;
      try {
        // First declaration wins; Grobal2.pas occasionally aliases idents and the earlier
        // name is the one the community sources use.
        names.putIfAbsent(field.getInt(null), field.getName());
      } catch (IllegalAccessException impossible) {
        throw new AssertionError(impossible);
      }
    }
    return Map.copyOf(names);
  }
}

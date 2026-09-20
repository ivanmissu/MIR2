package com.mir2.wiretool;

import com.mir2.gate.WireMessageCodec;
import com.mir2.protocol.MessageCodec;
import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Best-effort human-readable annotation of a recorded frame, used by {@code inspect} and by
 * replay diff reports. Never throws: unparseable frames degrade to a hex preview. Identification
 * is descriptive only — recording and replay never depend on it, so a wrong guess here can
 * never corrupt a capture.
 */
public final class FrameDescriber {
  private static final Map<Integer, String> CM_NAMES = namesWithPrefix("CM_");
  private static final Map<Integer, String> SM_NAMES = namesWithPrefix("SM_");
  private static final int MAX_BODY_PREVIEW = 48;
  private static final int MAX_HEX_PREVIEW = 24;

  private FrameDescriber() {}

  /** One line describing {@code payload} (frame payload without {@code '#'} / {@code '!'}). */
  public static String describe(Recording.Kind kind, byte[] payload) {
    if (kind == Recording.Kind.MARKER)
      return "marker \"" + new String(payload, StandardCharsets.UTF_8) + "\"";
    String label = switch (kind) {
      case CLIENT_FRAME -> "C→S";
      case SERVER_FRAME -> "S→C";
      case CLIENT_NOISE, SERVER_NOISE -> {
        yield "noise " + payload.length + "B " + printablePreview(payload);
      }
      default -> "marker";
    };
    if (!kind.frame()) return label;

    int offset = sequenceDigitOffset(kind, payload);
    // A client frame whose whole payload decodes to "**account/…" is the GAME gate's
    // headerless RunLogin first packet — it must be probed before the header parse, because
    // its 6-bit body would otherwise decode into a garbage-but-plausible message header.
    String runLogin = tryRunLogin(kind, payload, offset);
    if (runLogin != null) return label + " " + runLogin;
    String ack = tryActionAck(kind, payload);
    if (ack != null) return label + " " + ack;
    String packet = tryPacket(kind, payload, offset);
    if (packet != null) return label + " " + packet;
    return label + " unparsed " + payload.length + "B hex=" + hexPreview(payload);
  }

  /** Composes a compact ident name like {@code SM_SELECTSERVER_OK(1011)} for reports. */
  public static String identName(boolean clientToServer, int ident) {
    String name = (clientToServer ? CM_NAMES : SM_NAMES).get(ident);
    if (name == null) name = (clientToServer ? SM_NAMES : CM_NAMES).get(ident);
    return name == null ? "IDENT_" + ident : name + "(" + ident + ")";
  }

  /** {@code true} when the payload parses as a header+body packet, RunLogin, or action ack. */
  public static boolean parses(Recording.Kind kind, byte[] payload) {
    if (kind == Recording.Kind.MARKER) return true;
    if (!kind.frame()) return false;
    int offset = sequenceDigitOffset(kind, payload);
    return tryRunLogin(kind, payload, offset) != null || tryActionAck(kind, payload) != null
        || tryPacket(kind, payload, offset) != null;
  }

  private static String tryPacket(Recording.Kind kind, byte[] payload, int offset) {
    if (payload.length - offset < WireMessageCodec.WIRE_BYTES) return null;
    final DefaultMessage message;
    try {
      String header = new String(payload, offset, WireMessageCodec.WIRE_BYTES,
          StandardCharsets.ISO_8859_1);
      message = MessageCodec.decode(header);
    } catch (RuntimeException undecodable) {
      return null;
    }
    StringBuilder text = new StringBuilder();
    text.append(identName(kind.clientToServer(), message.ident()));
    text.append(" recog=").append(message.recog());
    text.append(" p=").append(message.param());
    text.append(" t=").append(message.tag());
    text.append(" s=").append(message.series());
    if (payload.length - offset > WireMessageCodec.WIRE_BYTES) {
      String encodedBody = new String(payload, offset + WireMessageCodec.WIRE_BYTES,
          payload.length - offset - WireMessageCodec.WIRE_BYTES, StandardCharsets.ISO_8859_1);
      text.append(" body=\"").append(decodedPreview(encodedBody)).append('"');
    }
    return text.toString();
  }

  /** Server-side raw action acknowledgements: {@code +GOOD/<tick>} / {@code +FAIL/<tick>}. */
  private static String tryActionAck(Recording.Kind kind, byte[] payload) {
    if (kind != Recording.Kind.SERVER_FRAME || payload.length < 7 || payload[0] != '+')
      return null;
    String text = new String(payload, StandardCharsets.ISO_8859_1);
    if (!text.startsWith("+GOOD/", 0) && !text.startsWith("+FAIL/", 0)) return null;
    for (int index = 6; index < text.length(); index++) {
      if (!Character.isDigit(text.charAt(index))) return null;
    }
    return "ack " + text.substring(0, 5) + " tick=" + text.substring(6);
  }

  private static String tryRunLogin(Recording.Kind kind, byte[] payload, int offset) {
    if (kind != Recording.Kind.CLIENT_FRAME || payload.length == offset) return null;
    final String decoded;
    try {
      decoded = WireMessageCodec.decodeBody(
          new String(payload, offset, payload.length - offset, StandardCharsets.ISO_8859_1));
    } catch (RuntimeException undecodable) {
      return null;
    }
    if (!decoded.startsWith("**")) return null;
    String[] fields = decoded.substring(2).split("/", -1);
    if (fields.length != 5) return null;
    // Never print certification codes into reports: mark the login code segment.
    return String.format(Locale.ROOT,
        "RunLogin account=%s character=%s cert=*** version=%s code=%s",
        fields[0], fields[1], fields[3], fields[4]);
  }

  private static String decodedPreview(String encodedBody) {
    try {
      return clip(WireMessageCodec.decodeBody(encodedBody));
    } catch (RuntimeException undecodable) {
      return "<undecodable " + encodedBody.length() + " chars>";
    }
  }

  private static String clip(String decoded) {
    StringBuilder clean = new StringBuilder(decoded.length());
    for (int index = 0; index < decoded.length(); index++) {
      char value = decoded.charAt(index);
      clean.append(value >= 0x20 && value != 0x7F ? value : '?');
    }
    return clean.length() <= MAX_BODY_PREVIEW
        ? clean.toString()
        : clean.substring(0, MAX_BODY_PREVIEW) + "…";
  }

  private static String printablePreview(byte[] payload) {
    StringBuilder preview = new StringBuilder();
    int limit = Math.min(payload.length, MAX_BODY_PREVIEW);
    for (int index = 0; index < limit; index++) {
      int value = payload[index] & 0xFF;
      preview.append(value >= 0x20 && value < 0x7F ? (char) value : '.');
    }
    if (payload.length > limit) preview.append('…');
    return "\"" + preview + "\"";
  }

  private static String hexPreview(byte[] payload) {
    StringBuilder hex = new StringBuilder();
    int limit = Math.min(payload.length, MAX_HEX_PREVIEW);
    for (int index = 0; index < limit; index++)
      hex.append(String.format(Locale.ROOT, "%02x", payload[index]));
    if (payload.length > limit) hex.append("…");
    return hex.toString();
  }

  /** Only client frames carry the Delphi client's rotating '1'..'9' sequence digit. */
  private static int sequenceDigitOffset(Recording.Kind kind, byte[] payload) {
    if (kind != Recording.Kind.CLIENT_FRAME) return 0;
    return payload.length > 0 && payload[0] >= '1' && payload[0] <= '9' ? 1 : 0;
  }

  private static Map<Integer, String> namesWithPrefix(String prefix) {
    Map<Integer, String> names = new HashMap<>();
    for (Field field : ProtocolConstants.class.getDeclaredFields()) {
      if (!field.getName().startsWith(prefix)) continue;
      if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) continue;
      try {
        names.putIfAbsent(field.getInt(null), field.getName());
      } catch (IllegalAccessException inaccessible) {
        throw new ExceptionInInitializerError(inaccessible);
      }
    }
    return names;
  }
}

package com.mir2.gate;

import java.util.Objects;

/**
 * The client read-burst guard — Delphi's oversized-packet kick in
 * {@code TFrmMain.ServerSocketClientRead} (RunGate/Main.pas:961-1005), configured by the four
 * constants in RunGate/GateShare.pas:96-100.
 *
 * <p>The check runs on <b>one socket read</b>, not on one protocol frame. Delphi calls
 * {@code Socket.ReceiveText} once and inspects whatever TCP happened to deliver, so the unit
 * being limited is "bytes that arrived together" and the message count is literally
 * {@code TagCount(sReviceMsg, '!')} — a count of frame terminators in that buffer
 * (Common/HUtil32.pas:1788-1796). The shape is a cheap-test/expensive-test pair:
 *
 * <pre>{@code
 * if nReviceLen > nNomClientPacketSize then            // 150 - the "normal" size
 *   nMsgCount := TagCount(sReviceMsg, '!');
 *   if (nMsgCount > nMaxClientMsgCount)                // 15 frames
 *      or (nReviceLen > nMaxClientPacketSize) then     // 7000 bytes
 *     kick
 * }</pre>
 *
 * <p>Three properties of that code are preserved here and are easy to get wrong:
 * <ul>
 *   <li><b>Everything at or below 150 bytes is unconditionally fine.</b> A read of 150 bytes
 *       containing 40 tiny frames is never counted, because the frame count is only computed
 *       inside the size branch. The message-count limit therefore cannot trigger on small
 *       reads at all.</li>
 *   <li><b>The comparisons are asymmetric.</b> Frame count uses {@code >} ({@code 16} frames
 *       trips a limit of 15) and so does the byte cap ({@code 7001} bytes trips 7000), but the
 *       gate into the branch is also {@code >}, so exactly 150 bytes stays out of it.</li>
 *   <li><b>A trip always discards the read</b> ({@code exit}), whether or not the connection is
 *       actually closed. When {@code bokickOverPacketSize} is False the socket survives but the
 *       data is dropped on the floor — a silent-truncation behaviour that is faithfully
 *       reproduced rather than "fixed".</li>
 * </ul>
 *
 * <p>{@code nMaxOverNomSizeCount = 2} (GateShare.pas:99) is declared in the same block but has
 * no reader anywhere in the shipped source — a vestigial "allow N oversized reads before
 * kicking" idea that was never wired up. It is deliberately not implemented.
 *
 * <p>Only RunGate (the 7200 game gate) has this guard; LoginGate and SelGate read without any
 * size test (LoginGate/Main.pas:290-327), which is why the policy is applied per gate kind.
 */
public record PacketSizePolicy(
    int normalSize,
    int maxSize,
    int maxMessagesPerRead,
    boolean kickOnOversize) {

  /** {@code nNomClientPacketSize} — below this, a read is never inspected. */
  public static final int DEFAULT_NORMAL_SIZE = 150;
  /** {@code nMaxClientPacketSize} — hard byte cap for a single read. */
  public static final int DEFAULT_MAX_SIZE = 7000;
  /** {@code nMaxClientMsgCount} — frame terminators allowed in one oversized read. */
  public static final int DEFAULT_MAX_MESSAGES = 15;

  public PacketSizePolicy {
    if (normalSize < 0) throw new IllegalArgumentException("normal packet size must not be negative");
    if (maxSize < 1) throw new IllegalArgumentException("max packet size must be positive");
    if (maxMessagesPerRead < 1) throw new IllegalArgumentException("max message count must be positive");
    if (maxSize < normalSize)
      throw new IllegalArgumentException("max packet size must be at least the normal packet size");
  }

  /** The shipped RunGate defaults (GateShare.pas:96-102, {@code bokickOverPacketSize = True}). */
  public static PacketSizePolicy defaults() {
    return new PacketSizePolicy(DEFAULT_NORMAL_SIZE, DEFAULT_MAX_SIZE, DEFAULT_MAX_MESSAGES, true);
  }

  /** The verdict for one socket read. */
  public enum Verdict {
    /** Within limits: hand the bytes to the protocol decoder. */
    ACCEPT,
    /** Over a limit: discard the read. The connection survives ({@code kickOnOversize=false}). */
    DISCARD,
    /** Over a limit and {@code bokickOverPacketSize}: discard, apply BlockMethod, close. */
    KICK
  }

  /**
   * Evaluates one read exactly as {@code ServerSocketClientRead} does.
   *
   * @param length     bytes delivered by this read ({@code Length(sReviceMsg)})
   * @param frameCount frame terminators in those bytes ({@code TagCount(sReviceMsg, '!')});
   *                   only consulted when the read exceeds {@link #normalSize()}
   */
  public Verdict evaluate(int length, int frameCount) {
    if (length <= normalSize) return Verdict.ACCEPT;
    boolean over = frameCount > maxMessagesPerRead || length > maxSize;
    if (!over) return Verdict.ACCEPT;
    return kickOnOversize ? Verdict.KICK : Verdict.DISCARD;
  }

  /** {@code TagCount(source, '!')} — counts frame terminators in a received buffer. */
  public static int countFrames(byte[] buffer, int offset, int length) {
    Objects.requireNonNull(buffer, "buffer");
    int count = 0;
    for (int index = offset; index < offset + length; index++) {
      if (buffer[index] == '!') count++;
    }
    return count;
  }
}

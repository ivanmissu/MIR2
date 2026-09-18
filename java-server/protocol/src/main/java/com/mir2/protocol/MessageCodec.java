package com.mir2.protocol;

/** Client-facing message codec: packed record then legacy 6-bit transport encoding. */
public final class MessageCodec { private MessageCodec(){}
 public static String encode(DefaultMessage m){return SixBitCodec.encodeString(m.toBytes());}
 public static DefaultMessage decode(String wire){return DefaultMessage.fromBytes(SixBitCodec.decodeString(wire));}
}

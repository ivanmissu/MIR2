import { DefaultMessage } from './DefaultMessage.js';
import { SixBitCodec } from './SixBitCodec.js';

/**
 * Client-facing message codec: packed record then legacy 6-bit transport encoding (16 bytes).
 */
export class MessageCodec {
  private constructor() {}

  public static encode(m: DefaultMessage): string {
    return SixBitCodec.encodeString(m.toBytes());
  }

  public static decode(wire: string): DefaultMessage {
    return DefaultMessage.fromBytes(SixBitCodec.decodeString(wire));
  }
}

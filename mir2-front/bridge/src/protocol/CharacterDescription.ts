import { SixBitCodec } from './SixBitCodec.js';

/**
 * Legacy packed TCharDesc: Feature(Integer) + Status(Integer), little-endian then 6-bit encoded (8 bytes -> encoded string).
 */
export class CharacterDescription {
  public static readonly BYTES = 8;

  constructor(
    public readonly feature: number,
    public readonly status: number
  ) {}

  public encode(): string {
    const buf = Buffer.alloc(CharacterDescription.BYTES);
    buf.writeInt32LE(this.feature, 0);
    buf.writeInt32LE(this.status, 4);
    return SixBitCodec.encodeString(buf);
  }

  public static decode(encoded: string): CharacterDescription {
    const bytes = SixBitCodec.decodeString(encoded);
    if (bytes.length < CharacterDescription.BYTES) {
      throw new Error(`TCharDesc must decode to at least 8 bytes, got ${bytes.length}`);
    }
    return new CharacterDescription(
      bytes.readInt32LE(0),
      bytes.readInt32LE(4)
    );
  }
}

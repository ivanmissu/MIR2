import { Ability } from '@mir2/shared';
import { SixBitCodec } from './SixBitCodec.js';

/**
 * Packed Delphi TAbility body used by SM_ABILITY.
 * Java's AbilityCodec is the source of truth; this reader intentionally keeps
 * the unsigned DWord experience values as JavaScript numbers (up to 2^32-1).
 */
export class AbilityCodec {
  public static readonly ABILITY_BYTES = 50;

  private constructor() {}

  public static decode(encodedBody: string): Ability {
    const bytes = SixBitCodec.decodeString(encodedBody);
    if (bytes.length !== AbilityCodec.ABILITY_BYTES) {
      throw new Error(
        `TAbility must decode to ${AbilityCodec.ABILITY_BYTES} bytes, got ${bytes.length}`
      );
    }

    const range = (offset: number): [number, number] => {
      const packed = bytes.readUInt32LE(offset);
      return [packed & 0xffff, packed >>> 16];
    };
    const [minAc, maxAc] = range(2);
    const [minDc, maxDc] = range(10);

    return {
      level: bytes.readUInt16LE(0),
      hp: bytes.readUInt16LE(22),
      maxHp: bytes.readUInt16LE(26),
      mp: bytes.readUInt16LE(24),
      maxMp: bytes.readUInt16LE(28),
      ac: minAc | (maxAc << 16),
      mac: bytes.readUInt32LE(6),
      dc: minDc | (maxDc << 16),
      mc: bytes.readUInt32LE(14),
      sc: bytes.readUInt32LE(18),
      exp: bytes.readUInt32LE(30),
      maxExp: bytes.readUInt32LE(34),
      weight: bytes.readUInt16LE(38),
      maxWeight: bytes.readUInt16LE(40)
    };
  }
}

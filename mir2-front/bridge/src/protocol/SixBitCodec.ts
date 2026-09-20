/**
 * Exact OLDMODE implementation of Common/EDcode.pas Encode6BitBuf/Decode6BitBuf
 * and java-server SixBitCodec.
 */
export class SixBitCodec {
  private constructor() {}

  public static encode(source: Uint8Array | Buffer): Buffer {
    const out: number[] = [];
    let rest = 0;
    let restBits = 0;

    for (let i = 0; i < source.length; i++) {
      const ch = source[i] & 255;
      const made = (rest | (ch >>> (2 + restBits))) & 63;
      rest = ((ch << (8 - (2 + restBits))) >>> 2) & 63;
      restBits += 2;
      out.push(made + 0x3c);
      if (restBits >= 6) {
        out.push(rest + 0x3c);
        restBits = 0;
        rest = 0;
      }
    }

    if (restBits > 0) {
      out.push(rest + 0x3c);
    }

    return Buffer.from(out);
  }

  public static decode(encoded: Uint8Array | Buffer): Buffer {
    const out: number[] = [];
    let bitPos = 2;
    let madeBits = 0;
    let tmp = 0;

    for (let i = 0; i < encoded.length; i++) {
      const raw = encoded[i] & 255;
      const ch = raw - 0x3c;
      if (ch < 0) {
        break;
      }
      if (madeBits + 6 >= 8) {
        out.push((tmp | ((ch & 63) >>> (6 - bitPos))) & 255);
        madeBits = 0;
        if (bitPos < 6) {
          bitPos += 2;
        } else {
          bitPos = 2;
          continue;
        }
      }
      tmp = ((ch << bitPos) & ((0xff << bitPos) & 0xff)) & 255;
      madeBits += 8 - bitPos;
    }

    return Buffer.from(out);
  }

  public static encodeString(source: Uint8Array | Buffer): string {
    return SixBitCodec.encode(source).toString('latin1');
  }

  public static decodeString(s: string): Buffer {
    return SixBitCodec.decode(Buffer.from(s, 'latin1'));
  }
}

import iconv from 'iconv-lite';

/**
 * Delphi AnsiString-compatible GBK string helpers.
 * Byte limits are strictly in bytes, never JavaScript UTF-16 code units.
 */
export class ByteStrings {
  private constructor() {}

  public static gbk(s: string): Buffer {
    return iconv.encode(s, 'gbk');
  }

  public static fromGbk(b: Uint8Array | Buffer): string {
    const buf = Buffer.isBuffer(b) ? b : Buffer.from(b);
    return iconv.decode(buf, 'gbk');
  }

  public static fixedGbk(s: string, max: number): Buffer {
    if (max < 0) {
      throw new Error('max must be non-negative');
    }
    if (max === 0 || s.length === 0) {
      return Buffer.alloc(0);
    }

    const fullBuf = ByteStrings.gbk(s);
    if (fullBuf.length <= max) {
      return fullBuf;
    }

    // Accumulate code points so we never cut in the middle of a multibyte GBK sequence
    let current = '';
    let lastValidBuf: Buffer = Buffer.alloc(0);

    for (const ch of s) {
      const nextStr = current + ch;
      const nextBuf = ByteStrings.gbk(nextStr);
      if (nextBuf.length > max) {
        break;
      }
      current = nextStr;
      lastValidBuf = Buffer.from(nextBuf);
    }

    return lastValidBuf;
  }

  public static fixedGbkText(s: string, max: number): string {
    return ByteStrings.fromGbk(ByteStrings.fixedGbk(s, max));
  }
}

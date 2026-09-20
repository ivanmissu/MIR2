/**
 * Packed Delphi TDefaultMessage: Integer + four Word fields, little endian (12 bytes).
 */
export class DefaultMessage {
  public readonly recog: number;
  public readonly ident: number;
  public readonly param: number;
  public readonly tag: number;
  public readonly series: number;

  constructor(recog: number, ident: number, param: number, tag: number, series: number) {
    if (
      (ident | param | tag | series) < 0 ||
      ident > 0xffff ||
      param > 0xffff ||
      tag > 0xffff ||
      series > 0xffff
    ) {
      throw new Error('Word field outside unsigned 16-bit range');
    }
    this.recog = recog | 0; // ensure 32-bit signed integer
    this.ident = ident;
    this.param = param;
    this.tag = tag;
    this.series = series;
  }

  public toBytes(): Buffer {
    const buf = Buffer.alloc(12);
    buf.writeInt32LE(this.recog, 0);
    buf.writeUInt16LE(this.ident, 4);
    buf.writeUInt16LE(this.param, 6);
    buf.writeUInt16LE(this.tag, 8);
    buf.writeUInt16LE(this.series, 10);
    return buf;
  }

  public static fromBytes(b: Uint8Array | Buffer): DefaultMessage {
    const buf = Buffer.isBuffer(b) ? b : Buffer.from(b);
    if (buf.length !== 12) {
      throw new Error(`TDefaultMessage must be exactly 12 bytes, got ${buf.length}`);
    }
    return new DefaultMessage(
      buf.readInt32LE(0),
      buf.readUInt16LE(4),
      buf.readUInt16LE(6),
      buf.readUInt16LE(8),
      buf.readUInt16LE(10)
    );
  }

  public equals(other: DefaultMessage | null | undefined): boolean {
    if (!other) return false;
    return (
      this.recog === other.recog &&
      this.ident === other.ident &&
      this.param === other.param &&
      this.tag === other.tag &&
      this.series === other.series
    );
  }
}

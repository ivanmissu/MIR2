import { describe, it, expect } from 'vitest';
import { SixBitCodec } from '../src/protocol/SixBitCodec.js';

describe('SixBitCodec', () => {
  it('round-trips 80 binary golden vectors', () => {
    for (let n = 0; n < 80; n++) {
      const b = Buffer.alloc(n);
      for (let i = 0; i < n; i++) {
        b[i] = (i * 37) & 0xff;
      }
      const encoded = SixBitCodec.encode(b);
      const decoded = SixBitCodec.decode(encoded);
      expect(Buffer.compare(b, decoded)).toBe(0);
    }
  });

  it('encodes and decodes strings via ISO-8859-1', () => {
    const original = Buffer.from('Hello, World! 12345', 'latin1');
    const encoded = SixBitCodec.encodeString(original);
    const decoded = SixBitCodec.decodeString(encoded);
    expect(decoded.toString('latin1')).toBe('Hello, World! 12345');
  });

  it('produces valid 6-bit characters (>= 0x3c and <= 0x7b)', () => {
    const b = Buffer.from([0x00, 0xff, 0x55, 0xaa, 0x12, 0x34, 0x78]);
    const encoded = SixBitCodec.encode(b);
    for (let i = 0; i < encoded.length; i++) {
      expect(encoded[i]).toBeGreaterThanOrEqual(0x3c);
      expect(encoded[i]).toBeLessThanOrEqual(0x7b);
    }
  });
});

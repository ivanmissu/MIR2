import { describe, it, expect } from 'vitest';
import { DefaultMessage } from '../src/protocol/DefaultMessage.js';
import { MessageCodec } from '../src/protocol/MessageCodec.js';

describe('DefaultMessage & MessageCodec', () => {
  it('packed message is little-endian (Java ProtocolTest parity)', () => {
    const msg = new DefaultMessage(0x01020304, 0x1234, 0xffff, 0x8000, 0x5678);
    const expected = Buffer.from([
      4, 3, 2, 1,
      0x34, 0x12,
      0xff, 0xff,
      0x00, 0x80,
      0x78, 0x56
    ]);
    expect(Buffer.compare(msg.toBytes(), expected)).toBe(0);
  });

  it('message round-trips twenty golden vectors (Java ProtocolTest parity)', () => {
    for (let i = 0; i < 20; i++) {
      const recog = (i * 7919 - 1000) | 0;
      const ident = i;
      const param = (i * 17) & 0xffff;
      const tag = (0xffff - i) & 0xffff;
      const series = (i * 31) & 0xffff;

      const m = new DefaultMessage(recog, ident, param, tag, series);
      const encoded = MessageCodec.encode(m);
      expect(encoded.length).toBe(16);

      const decoded = MessageCodec.decode(encoded);
      expect(decoded.recog).toBe(m.recog);
      expect(decoded.ident).toBe(m.ident);
      expect(decoded.param).toBe(m.param);
      expect(decoded.tag).toBe(m.tag);
      expect(decoded.series).toBe(m.series);
      expect(decoded.equals(m)).toBe(true);
    }
  });

  it('rejects out of range Word fields', () => {
    expect(() => new DefaultMessage(0, -1, 0, 0, 0)).toThrow();
    expect(() => new DefaultMessage(0, 0x10000, 0, 0, 0)).toThrow();
    expect(() => new DefaultMessage(0, 0, -1, 0, 0)).toThrow();
    expect(() => new DefaultMessage(0, 0, 0, -1, 0)).toThrow();
    expect(() => new DefaultMessage(0, 0, 0, 0, -1)).toThrow();
  });
});

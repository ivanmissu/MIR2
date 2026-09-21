import { describe, expect, it } from 'vitest';
import { AbilityCodec } from '../src/protocol/AbilityCodec.js';
import { ClientItemCodec } from '../src/protocol/ClientItemCodec.js';
import { SixBitCodec } from '../src/protocol/SixBitCodec.js';
import { BackpackItem } from '@mir2/shared';

describe('W12/W15 wire readers', () => {
  it('decodes the packed 50-byte TAbility body', () => {
    const bytes = Buffer.alloc(50);
    bytes.writeUInt16LE(7, 0);
    bytes.writeUInt32LE((12 << 16) | 3, 2); // AC 3-12
    bytes.writeUInt32LE((9 << 16) | 4, 10); // DC 4-9
    bytes.writeUInt16LE(80, 22);
    bytes.writeUInt16LE(25, 24);
    bytes.writeUInt16LE(100, 26);
    bytes.writeUInt16LE(40, 28);
    bytes.writeUInt32LE(1234, 30);
    bytes.writeUInt32LE(5678, 34);

    const ability = AbilityCodec.decode(SixBitCodec.encodeString(bytes));
    expect(ability).toMatchObject({
      level: 7,
      hp: 80,
      maxHp: 100,
      mp: 25,
      maxMp: 40,
      ac: (12 << 16) | 3,
      dc: (9 << 16) | 4,
      exp: 1234,
      maxExp: 5678
    });
  });

  it('decodes the slot/item pairs used by SM_SENDUSEITEMS', () => {
    const item: BackpackItem = {
      item: {
        name: '木剑', stdMode: 5, shape: 0, weight: 2, aniCount: 0, source: 0,
        needIdentify: 0, looks: 1, duraMax: 20, ac: 0, mac: 0, dc: 0x00050002,
        mc: 0, sc: 0, need: 0, needLevel: 0, price: 400
      },
      makeIndex: 99, dura: 15, duraMax: 20
    };
    const decoded = ClientItemCodec.decodeWornSet(`1/${ClientItemCodec.encode(item)}/`);
    expect(decoded.get(1)?.makeIndex).toBe(99);
    expect(() => ClientItemCodec.decodeWornSet('13/bad/')).toThrow();
  });
});

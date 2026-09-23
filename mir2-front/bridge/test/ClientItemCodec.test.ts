import { describe, it, expect } from 'vitest';
import { BackpackItem, StdItemData } from '@mir2/shared';
import { ClientItemCodec } from '../src/protocol/ClientItemCodec.js';

describe('ClientItemCodec', () => {
  const woodenSword: StdItemData = {
    name: '木剑',
    stdMode: 5,
    shape: 0,
    weight: 2,
    aniCount: 0,
    source: 0,
    needIdentify: 0,
    looks: 1,
    duraMax: 20,
    ac: 0,
    mac: 0,
    dc: 0x00050002,
    mc: 0,
    sc: 0,
    need: 0,
    needLevel: 0,
    price: 400
  };

  const woodenSwordItem: BackpackItem = {
    item: woodenSword,
    makeIndex: 0x1234,
    dura: 20,
    duraMax: 20
  };

  it('encodes the 76-byte Delphi layout byte for byte (Java ClientItemCodecTest parity)', () => {
    const bytes = ClientItemCodec.bytes(woodenSwordItem);
    expect(bytes.length).toBe(76);

    // String[20] slot: length byte, GBK payload (木=C4BE 剑=BDA3), zero padding to 21.
    expect(bytes[0]).toBe(4);
    expect(bytes[1]).toBe(0xc4);
    expect(bytes[2]).toBe(0xbe);
    expect(bytes[3]).toBe(0xbd);
    expect(bytes[4]).toBe(0xa3);
    for (let pad = 5; pad < 21; pad++) {
      expect(bytes[pad]).toBe(0);
    }

    expect(bytes[ClientItemCodec.STD_MODE_OFFSET]).toBe(5);
    expect(bytes[ClientItemCodec.SHAPE_OFFSET]).toBe(0);
    expect(bytes[ClientItemCodec.WEIGHT_OFFSET]).toBe(2);
    expect(bytes[ClientItemCodec.ANI_COUNT_OFFSET]).toBe(0);
    expect(bytes[ClientItemCodec.SOURCE_OFFSET]).toBe(0);
    expect(bytes[ClientItemCodec.RESERVED_OFFSET]).toBe(0);
    expect(bytes[ClientItemCodec.NEED_IDENTIFY_OFFSET]).toBe(0);
    expect(bytes.readUInt16LE(ClientItemCodec.LOOKS_OFFSET)).toBe(1);
    expect(bytes.readUInt32LE(ClientItemCodec.DURA_MAX_OFFSET)).toBe(20);
    expect(bytes.readUInt32LE(ClientItemCodec.AC_OFFSET)).toBe(0);
    expect(bytes.readUInt32LE(ClientItemCodec.MAC_OFFSET)).toBe(0);
    expect(bytes.readUInt32LE(ClientItemCodec.DC_OFFSET)).toBe(0x00050002);
    expect(bytes.readUInt32LE(ClientItemCodec.MC_OFFSET)).toBe(0);
    expect(bytes.readUInt32LE(ClientItemCodec.SC_OFFSET)).toBe(0);
    expect(bytes.readUInt32LE(ClientItemCodec.NEED_OFFSET)).toBe(0);
    expect(bytes.readUInt32LE(ClientItemCodec.NEED_LEVEL_OFFSET)).toBe(0);
    expect(bytes.readUInt32LE(ClientItemCodec.PRICE_OFFSET)).toBe(400);

    // Padding at 66..67
    expect(bytes[66]).toBe(0);
    expect(bytes[67]).toBe(0);

    expect(bytes.readInt32LE(ClientItemCodec.MAKE_INDEX_OFFSET)).toBe(0x1234);
    expect(bytes.readUInt16LE(ClientItemCodec.DURA_OFFSET)).toBe(20);
    expect(bytes.readUInt16LE(ClientItemCodec.DURA_MAX_INSTANCE_OFFSET)).toBe(20);
  });

  it('round-trips through the 6-bit encoded body', () => {
    const encoded = ClientItemCodec.encode(woodenSwordItem);
    const decoded = ClientItemCodec.decode(encoded);

    expect(decoded.item.name).toBe('木剑');
    expect(decoded.item.stdMode).toBe(5);
    expect(decoded.item.reserved).toBe(0);
    expect(decoded.item.looks).toBe(1);
    expect(decoded.item.dc).toBe(0x00050002);
    expect(decoded.item.price).toBe(400);
    expect(decoded.makeIndex).toBe(0x1234);
    expect(decoded.dura).toBe(20);
    expect(decoded.duraMax).toBe(20);
  });

  it('keeps Reserved and NeedIdentify as separate TStdItem bytes', () => {
    const prayerBlade: BackpackItem = {
      item: {
        ...woodenSword,
        name: '祈祷之刃',
        reserved: 8,
        needIdentify: 0,
        looks: 66
      },
      makeIndex: 710,
      dura: 20,
      duraMax: 20
    };

    const bytes = ClientItemCodec.bytes(prayerBlade);
    expect(bytes[ClientItemCodec.RESERVED_OFFSET]).toBe(8);
    expect(bytes[ClientItemCodec.NEED_IDENTIFY_OFFSET]).toBe(0);

    const decoded = ClientItemCodec.decode(ClientItemCodec.encode(prayerBlade));
    expect(decoded.item.reserved).toBe(8);
    expect(decoded.item.needIdentify).toBe(0);
  });

  it('packs a full twenty-byte GBK name without splitting a character', () => {
    const fullNamed: StdItemData = {
      name: '一一一一一一一一一一', // 10 CJK characters = 20 GBK bytes
      stdMode: 1,
      shape: 0,
      weight: 1,
      aniCount: 0,
      source: 0,
      needIdentify: 0,
      looks: 2,
      duraMax: 0,
      ac: 0,
      mac: 0,
      dc: 0,
      mc: 0,
      sc: 0,
      need: 0,
      needLevel: 0,
      price: 0
    };
    const item: BackpackItem = {
      item: fullNamed,
      makeIndex: 1,
      dura: 0,
      duraMax: 0
    };

    const bytes = ClientItemCodec.bytes(item);
    expect(bytes[0]).toBe(20);
    expect(bytes[20]).toBe(0xbb); // 一 in GBK is D2BB
    expect(bytes[ClientItemCodec.STD_MODE_OFFSET]).toBe(1);

    const decoded = ClientItemCodec.decode(ClientItemCodec.encode(item));
    expect(decoded.item.name).toBe('一一一一一一一一一一');
  });

  it('joins bag bodies with the Delphi trailing separator', () => {
    const chickenMeat: BackpackItem = {
      item: {
        name: '鸡肉',
        stdMode: 0,
        shape: 0,
        weight: 1,
        aniCount: 0,
        source: 0,
        needIdentify: 0,
        looks: 41,
        duraMax: 10,
        ac: 0,
        mac: 0,
        dc: 0,
        mc: 0,
        sc: 0,
        need: 0,
        needLevel: 0,
        price: 10
      },
      makeIndex: 2,
      dura: 10,
      duraMax: 10
    };

    const bag = [woodenSwordItem, chickenMeat];
    const encodedBag = ClientItemCodec.encodeBag(bag);
    expect(encodedBag).toBe(
      ClientItemCodec.encode(woodenSwordItem) + '/' + ClientItemCodec.encode(chickenMeat) + '/'
    );

    const decodedBag = ClientItemCodec.decodeBag(encodedBag);
    expect(decodedBag.length).toBe(2);
    expect(decodedBag[0].item.name).toBe('木剑');
    expect(decodedBag[1].item.name).toBe('鸡肉');

    expect(ClientItemCodec.encodeBag([])).toBe('');
    expect(ClientItemCodec.decodeBag('')).toEqual([]);
  });

  it('rejects bodies that do not decode to 76 bytes', () => {
    expect(() => ClientItemCodec.decode('short')).toThrow();
  });
});

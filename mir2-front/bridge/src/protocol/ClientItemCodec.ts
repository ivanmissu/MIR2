import { BackpackItem, StdItemData } from '@mir2/shared';
import { ByteStrings } from './ByteStrings.js';
import { SixBitCodec } from './SixBitCodec.js';

/**
 * Legacy TClientItem wire codec (Common/Grobal2.pas:562, Client/Grobal2.pas:558).
 * Wire size is exactly 76 bytes.
 */
export class ClientItemCodec {
  public static readonly STD_ITEM_BYTES = 66;
  public static readonly CLIENT_ITEM_BYTES = 76;
  public static readonly MAX_NAME_BYTES = 20;

  public static readonly NAME_BYTES = 21;
  public static readonly STD_MODE_OFFSET = 21;
  public static readonly SHAPE_OFFSET = 22;
  public static readonly WEIGHT_OFFSET = 23;
  public static readonly ANI_COUNT_OFFSET = 24;
  public static readonly SOURCE_OFFSET = 25;
  public static readonly RESERVED_OFFSET = 26;
  public static readonly NEED_IDENTIFY_OFFSET = 27;
  public static readonly LOOKS_OFFSET = 28;
  public static readonly DURA_MAX_OFFSET = 30;
  public static readonly AC_OFFSET = 34;
  public static readonly MAC_OFFSET = 38;
  public static readonly DC_OFFSET = 42;
  public static readonly MC_OFFSET = 46;
  public static readonly SC_OFFSET = 50;
  public static readonly NEED_OFFSET = 54;
  public static readonly NEED_LEVEL_OFFSET = 58;
  public static readonly PRICE_OFFSET = 62;
  public static readonly MAKE_INDEX_OFFSET = 68;
  public static readonly DURA_OFFSET = 72;
  public static readonly DURA_MAX_INSTANCE_OFFSET = 74;

  public static readonly SEPARATOR = '/';

  private constructor() {}

  public static bytes(item: BackpackItem): Buffer {
    const std = item.item;
    const buffer = Buffer.alloc(ClientItemCodec.CLIENT_ITEM_BYTES);

    // String[20] ShortString slot: length byte, GBK payload, zero padding to offset 21.
    const nameBytes = ByteStrings.fixedGbk(std.name, ClientItemCodec.MAX_NAME_BYTES);
    buffer.writeUInt8(nameBytes.length, 0);
    nameBytes.copy(buffer, 1);

    buffer.writeUInt8(std.stdMode & 0xff, ClientItemCodec.STD_MODE_OFFSET);
    buffer.writeUInt8(std.shape & 0xff, ClientItemCodec.SHAPE_OFFSET);
    buffer.writeUInt8(std.weight & 0xff, ClientItemCodec.WEIGHT_OFFSET);
    buffer.writeUInt8(std.aniCount & 0xff, ClientItemCodec.ANI_COUNT_OFFSET);
    buffer.writeInt8(std.source, ClientItemCodec.SOURCE_OFFSET);
    buffer.writeUInt8((std.reserved ?? 0) & 0xff, ClientItemCodec.RESERVED_OFFSET);
    buffer.writeUInt8(std.needIdentify & 0xff, ClientItemCodec.NEED_IDENTIFY_OFFSET);
    buffer.writeUInt16LE(std.looks & 0xffff, ClientItemCodec.LOOKS_OFFSET);
    buffer.writeUInt32LE(std.duraMax >>> 0, ClientItemCodec.DURA_MAX_OFFSET);
    buffer.writeUInt32LE(std.ac >>> 0, ClientItemCodec.AC_OFFSET);
    buffer.writeUInt32LE(std.mac >>> 0, ClientItemCodec.MAC_OFFSET);
    buffer.writeUInt32LE(std.dc >>> 0, ClientItemCodec.DC_OFFSET);
    buffer.writeUInt32LE(std.mc >>> 0, ClientItemCodec.MC_OFFSET);
    buffer.writeUInt32LE(std.sc >>> 0, ClientItemCodec.SC_OFFSET);
    buffer.writeUInt32LE(std.need >>> 0, ClientItemCodec.NEED_OFFSET);
    buffer.writeUInt32LE(std.needLevel >>> 0, ClientItemCodec.NEED_LEVEL_OFFSET);
    buffer.writeUInt32LE(std.price >>> 0, ClientItemCodec.PRICE_OFFSET);

    // Bytes 66..67 stay zero: alignment padding before MakeIndex
    buffer.writeInt32LE(item.makeIndex, ClientItemCodec.MAKE_INDEX_OFFSET);
    buffer.writeUInt16LE(item.dura & 0xffff, ClientItemCodec.DURA_OFFSET);
    buffer.writeUInt16LE(item.duraMax & 0xffff, ClientItemCodec.DURA_MAX_INSTANCE_OFFSET);

    return buffer;
  }

  public static encode(item: BackpackItem): string {
    return SixBitCodec.encodeString(ClientItemCodec.bytes(item));
  }

  public static encodeBag(backpack: BackpackItem[]): string {
    if (backpack.length === 0) return '';
    let body = '';
    for (const item of backpack) {
      body += ClientItemCodec.encode(item) + ClientItemCodec.SEPARATOR;
    }
    return body;
  }

  public static decode(encoded: string): BackpackItem {
    const bytes = SixBitCodec.decodeString(encoded);
    if (bytes.length !== ClientItemCodec.CLIENT_ITEM_BYTES) {
      throw new Error(
        `TClientItem must decode to ${ClientItemCodec.CLIENT_ITEM_BYTES} bytes, got ${bytes.length}`
      );
    }
    const nameLength = bytes.readUInt8(0);
    if (nameLength > ClientItemCodec.MAX_NAME_BYTES) {
      throw new Error(`TClientItem name length byte out of range: ${nameLength}`);
    }
    const name = ByteStrings.fromGbk(bytes.subarray(1, 1 + nameLength));
    const std: StdItemData = {
      name,
      stdMode: bytes.readUInt8(ClientItemCodec.STD_MODE_OFFSET),
      shape: bytes.readUInt8(ClientItemCodec.SHAPE_OFFSET),
      weight: bytes.readUInt8(ClientItemCodec.WEIGHT_OFFSET),
      aniCount: bytes.readUInt8(ClientItemCodec.ANI_COUNT_OFFSET),
      source: bytes.readInt8(ClientItemCodec.SOURCE_OFFSET),
      reserved: bytes.readUInt8(ClientItemCodec.RESERVED_OFFSET),
      needIdentify: bytes.readUInt8(ClientItemCodec.NEED_IDENTIFY_OFFSET),
      looks: bytes.readUInt16LE(ClientItemCodec.LOOKS_OFFSET),
      duraMax: bytes.readUInt32LE(ClientItemCodec.DURA_MAX_OFFSET),
      ac: bytes.readUInt32LE(ClientItemCodec.AC_OFFSET),
      mac: bytes.readUInt32LE(ClientItemCodec.MAC_OFFSET),
      dc: bytes.readUInt32LE(ClientItemCodec.DC_OFFSET),
      mc: bytes.readUInt32LE(ClientItemCodec.MC_OFFSET),
      sc: bytes.readUInt32LE(ClientItemCodec.SC_OFFSET),
      need: bytes.readUInt32LE(ClientItemCodec.NEED_OFFSET),
      needLevel: bytes.readUInt32LE(ClientItemCodec.NEED_LEVEL_OFFSET),
      price: bytes.readUInt32LE(ClientItemCodec.PRICE_OFFSET)
    };

    return {
      item: std,
      makeIndex: bytes.readInt32LE(ClientItemCodec.MAKE_INDEX_OFFSET),
      dura: bytes.readUInt16LE(ClientItemCodec.DURA_OFFSET),
      duraMax: bytes.readUInt16LE(ClientItemCodec.DURA_MAX_INSTANCE_OFFSET)
    };
  }

  public static decodeBag(encodedBag: string): BackpackItem[] {
    if (!encodedBag || encodedBag.trim() === '') return [];
    const parts = encodedBag.split(ClientItemCodec.SEPARATOR).filter(p => p.length > 0);
    return parts.map(part => ClientItemCodec.decode(part));
  }

  /**
   * Decodes the legacy SM_SENDUSEITEMS body: slot/encoded TClientItem/ repeated.
   * The slot number and separators are deliberately outside the six-bit item payload,
   * matching GameProtocolAdapter.sendWornSet on the Java side.
   */
  public static decodeWornSet(body: string): Map<number, BackpackItem> {
    const worn = new Map<number, BackpackItem>();
    if (!body) return worn;
    const parts = body.split(ClientItemCodec.SEPARATOR).filter(part => part.length > 0);
    if (parts.length % 2 !== 0) {
      throw new Error(`SM_SENDUSEITEMS body has an incomplete slot/item pair: ${parts.length}`);
    }
    for (let index = 0; index < parts.length; index += 2) {
      const slot = Number.parseInt(parts[index], 10);
      if (!Number.isInteger(slot) || slot < 0 || slot > 12) {
        throw new Error(`SM_SENDUSEITEMS slot out of range: ${parts[index]}`);
      }
      worn.set(slot, ClientItemCodec.decode(parts[index + 1]));
    }
    return worn;
  }
}

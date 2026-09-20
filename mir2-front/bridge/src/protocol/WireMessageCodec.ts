import { ByteStrings } from './ByteStrings.js';
import { DefaultMessage } from './DefaultMessage.js';
import { MessageCodec } from './MessageCodec.js';
import { SixBitCodec } from './SixBitCodec.js';

export interface WirePacket {
  message: DefaultMessage;
  encodedBody: string;
}

export interface RunLogin {
  account: string;
  characterName: string;
  certification: number;
  clientVersion: number;
  loginCode: number;
}

export type GameStatusFrame = {
  type: 'status';
  accepted: boolean;
  serverTick: number;
};

export type GamePacketFrame = {
  type: 'packet';
  packet: WirePacket;
};

export type ParsedFrame = GameStatusFrame | GamePacketFrame;

/**
 * Codec for the legacy `#<sequence?><header><body>!` TCP packet format.
 */
export class WireMessageCodec {
  public static readonly WIRE_BYTES = 16;
  public static readonly MAX_PACKET_BYTES = 8 * 1024;
  public static readonly START = 0x23; // '#'
  public static readonly END = 0x21; // '!'

  private constructor() {}

  public static encodeBody(text: string): string {
    return SixBitCodec.encodeString(ByteStrings.gbk(text));
  }

  public static decodeBody(encodedBody: string): string {
    if (!encodedBody || encodedBody.length === 0) return '';
    return ByteStrings.fromGbk(SixBitCodec.decodeString(encodedBody));
  }

  /**
   * Formats a standard WirePacket into a wire string/buffer.
   */
  public static formatPacket(packet: WirePacket, sequence?: number): Buffer {
    const seqStr = sequence !== undefined && sequence >= 1 && sequence <= 9 ? String(sequence) : '';
    const headerStr = MessageCodec.encode(packet.message);
    const bodyStr = packet.encodedBody || '';
    const frame = '#' + seqStr + headerStr + bodyStr + '!';
    return Buffer.from(frame, 'latin1');
  }

  /**
   * Formats a status acknowledgement frame (#+GOOD/<tick>! or #+FAIL/<tick>!).
   */
  public static formatStatus(accepted: boolean, tick: number): Buffer {
    const text = accepted ? '+GOOD/' : '+FAIL/';
    return Buffer.from('#' + text + tick + '!', 'ascii');
  }

  /**
   * Formats the GAME connection's first RunLogin packet.
   */
  public static formatRunLogin(login: RunLogin, sequence = 1): Buffer {
    const plain = `**${login.account}/${login.characterName}/${login.certification}/${login.clientVersion}/${login.loginCode}`;
    const encoded = WireMessageCodec.encodeBody(plain);
    const seqStr = sequence >= 1 && sequence <= 9 ? String(sequence) : '1';
    return Buffer.from('#' + seqStr + encoded + '!', 'latin1');
  }

  /**
   * Parses a single frame payload (the content between '#' and '!').
   */
  public static parseFrame(payload: Buffer | Uint8Array): ParsedFrame {
    const buf = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
    const str = buf.toString('latin1');

    // Check for status frame: +GOOD/<tick> or +FAIL/<tick>
    if (str.startsWith('+')) {
      const isGood = str.startsWith('+GOOD/');
      const tickStr = str.substring(isGood ? 6 : 6);
      const tick = parseInt(tickStr, 10) || 0;
      return {
        type: 'status',
        accepted: isGood,
        serverTick: tick
      };
    }

    const offset = WireMessageCodec.hasSequence(str) ? 1 : 0;
    if (str.length - offset < WireMessageCodec.WIRE_BYTES) {
      throw new Error(`Frame shorter than 16-byte message header: length=${str.length}, offset=${offset}`);
    }

    const headerStr = str.substring(offset, offset + WireMessageCodec.WIRE_BYTES);
    const bodyStr = str.substring(offset + WireMessageCodec.WIRE_BYTES);
    const message = MessageCodec.decode(headerStr);

    return {
      type: 'packet',
      packet: {
        message,
        encodedBody: bodyStr
      }
    };
  }

  /**
   * Parses a RunLogin payload from the raw frame between '#' and '!'.
   */
  public static parseRunLogin(payload: Buffer | Uint8Array): RunLogin {
    const buf = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
    const str = buf.toString('latin1');
    const offset = WireMessageCodec.hasSequence(str) ? 1 : 0;
    const encoded = str.substring(offset);

    let decoded: string;
    try {
      decoded = WireMessageCodec.decodeBody(encoded);
    } catch (err) {
      throw new Error(`Invalid RunLogin encoding: ${(err as Error).message}`);
    }

    if (!decoded.startsWith('**')) {
      throw new Error('Invalid RunLogin prefix');
    }

    const fields = decoded.substring(2).split('/');
    if (fields.length !== 5) {
      throw new Error(`Invalid RunLogin field count: expected 5, got ${fields.length}`);
    }

    const cert = parseInt(fields[2], 10);
    const version = parseInt(fields[3], 10);
    const code = parseInt(fields[4], 10);

    if (isNaN(cert) || cert <= 0) throw new Error('Invalid certification');
    if (isNaN(version) || version <= 0) throw new Error('Invalid client version');
    if (isNaN(code) || code < 0) throw new Error('Invalid login code');

    return {
      account: fields[0],
      characterName: fields[1],
      certification: cert,
      clientVersion: version,
      loginCode: code
    };
  }

  public static isStatusFrame(strOrBuf: string | Buffer): boolean {
    const str = typeof strOrBuf === 'string' ? strOrBuf : strOrBuf.toString('latin1');
    return str.startsWith('+');
  }

  private static hasSequence(str: string): boolean {
    return str.length > 0 && str[0] >= '1' && str[0] <= '9';
  }
}

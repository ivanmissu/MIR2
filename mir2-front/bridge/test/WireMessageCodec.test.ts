import { describe, it, expect } from 'vitest';
import { DefaultMessage } from '../src/protocol/DefaultMessage.js';
import { WireMessageCodec, WirePacket, ParsedFrame } from '../src/protocol/WireMessageCodec.js';

describe('WireMessageCodec', () => {
  it('framed packet round-trips header and GBK body (Java WireMessageCodecTest parity)', () => {
    const original: WirePacket = {
      message: new DefaultMessage(7, 2001, 2, 3, 4),
      encodedBody: WireMessageCodec.encodeBody('账号/口令')
    };

    const formatted = WireMessageCodec.formatPacket(original);
    // Strip leading '#' and trailing '!'
    expect(formatted[0]).toBe(0x23); // '#'
    expect(formatted[formatted.length - 1]).toBe(0x21); // '!'

    const payload = formatted.subarray(1, formatted.length - 1);
    const parsed = WireMessageCodec.parseFrame(payload);

    expect(parsed.type).toBe('packet');
    if (parsed.type === 'packet') {
      expect(parsed.packet.message.equals(original.message)).toBe(true);
      expect(WireMessageCodec.decodeBody(parsed.packet.encodedBody)).toBe('账号/口令');
    }
  });

  it('strips client sequence digit (Java WireMessageCodecTest parity)', () => {
    const packet: WirePacket = {
      message: new DefaultMessage(0, 100, 0, 0, 0),
      encodedBody: WireMessageCodec.encodeBody('hero/42')
    };

    const clientFormatted = WireMessageCodec.formatPacket(packet, 7);
    expect(clientFormatted.toString('latin1').startsWith('#7')).toBe(true);

    const payload = clientFormatted.subarray(1, clientFormatted.length - 1);
    const parsed = WireMessageCodec.parseFrame(payload);

    expect(parsed.type).toBe('packet');
    if (parsed.type === 'packet') {
      expect(parsed.packet.message.equals(packet.message)).toBe(true);
      expect(WireMessageCodec.decodeBody(parsed.packet.encodedBody)).toBe('hero/42');
    }
  });

  it('reads headerless GAME RunLogin packet (Java WireMessageCodecTest parity)', () => {
    const login = {
      account: '账号',
      characterName: '战士',
      certification: 2345,
      clientVersion: 120040918,
      loginCode: 9
    };

    const frame = WireMessageCodec.formatRunLogin(login, 4);
    expect(frame.toString('latin1').startsWith('#4')).toBe(true);

    const payload = frame.subarray(1, frame.length - 1);
    const parsed = WireMessageCodec.parseRunLogin(payload);

    expect(parsed.account).toBe('账号');
    expect(parsed.characterName).toBe('战士');
    expect(parsed.certification).toBe(2345);
    expect(parsed.clientVersion).toBe(120040918);
    expect(parsed.loginCode).toBe(9);
  });

  it('writes and parses legacy GOOD and FAIL status frames (Java WireMessageCodecTest parity)', () => {
    const goodFrame = WireMessageCodec.formatStatus(true, 1234);
    const failFrame = WireMessageCodec.formatStatus(false, 5678);

    expect(goodFrame.toString('ascii')).toBe('#+GOOD/1234!');
    expect(failFrame.toString('ascii')).toBe('#+FAIL/5678!');

    const parsedGood = WireMessageCodec.parseFrame(goodFrame.subarray(1, goodFrame.length - 1));
    const parsedFail = WireMessageCodec.parseFrame(failFrame.subarray(1, failFrame.length - 1));

    expect(parsedGood).toEqual({ type: 'status', accepted: true, serverTick: 1234 });
    expect(parsedFail).toEqual({ type: 'status', accepted: false, serverTick: 5678 });
  });
});

import { describe, it, expect } from 'vitest';
import { CharacterDescription } from '../src/protocol/CharacterDescription.js';

describe('CharacterDescription', () => {
  it('round-trips feature and status through 8-byte LE 6-bit encoding', () => {
    const desc = new CharacterDescription(0x01050100, 0x00000002);
    const encoded = desc.encode();
    const decoded = CharacterDescription.decode(encoded);

    expect(decoded.feature).toBe(0x01050100);
    expect(decoded.status).toBe(0x00000002);
  });
});

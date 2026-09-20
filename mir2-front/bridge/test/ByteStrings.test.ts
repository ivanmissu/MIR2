import { describe, it, expect } from 'vitest';
import { ByteStrings } from '../src/protocol/ByteStrings.js';

describe('ByteStrings', () => {
  it('byte limit is gbk-aware (Java ProtocolTest parity)', () => {
    expect(ByteStrings.fixedGbkText('传奇英雄', 4)).toBe('传奇');
    expect(ByteStrings.fixedGbkText('传奇英雄', 5)).toBe('传奇');
    expect(ByteStrings.fixedGbkText('传奇英雄', 3)).toBe('传');
    expect(ByteStrings.fixedGbkText('传奇英雄', 8)).toBe('传奇英雄');
    expect(ByteStrings.fixedGbkText('传奇英雄', 10)).toBe('传奇英雄');
    expect(ByteStrings.fixedGbkText('传奇英雄', 0)).toBe('');
  });

  it('handles mixed ASCII and Chinese characters', () => {
    expect(ByteStrings.fixedGbkText('Mir2传奇', 6)).toBe('Mir2传');
    expect(ByteStrings.fixedGbkText('Mir2传奇', 5)).toBe('Mir2');
  });
});

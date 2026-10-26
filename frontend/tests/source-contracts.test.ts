// @vitest-environment node
import { it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import hashes from '../contract/source-hashes.json';
it('generated public client matches contract revisions with both LF and CRLF checkouts', () => {
  for (const [file, hash] of Object.entries(hashes)) {
    const path = new URL('../../' + file, import.meta.url);
    if (!existsSync(path)) continue;
    const source = readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
    for (const checkout of [source, source.replace(/\n/g, '\r\n')]) {
      expect(
        createHash('sha256').update(checkout.replace(/\r\n/g, '\n')).digest('hex'),
        file + ' changed; regenerate frontend DTOs',
      ).toBe(hash);
    }
  }
});

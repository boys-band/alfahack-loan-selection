import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import {
  copyFileSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

test('real pre-commit formats staged frontend code while preserving unstaged changes and generated files', () => {
  const root = mkdtempSync(join(tmpdir(), 'alfa-hook-'));
  const env = { ...process.env, HUSKY: '1' };
  const git = (...args) => execFileSync('git', args, { cwd: root, env, encoding: 'utf8' });
  try {
    for (const path of ['.husky', 'scripts', 'frontend/src/api'])
      mkdirSync(join(root, path), { recursive: true });
    for (const path of [
      'package.json',
      'lint-staged.config.mjs',
      '.husky/pre-commit',
      'scripts/format-frontend.mjs',
      'frontend/.prettierrc.json',
      'frontend/.prettierignore',
    ]) {
      copyFileSync(join(repository, path), join(root, path));
    }
    symlinkSync(
      join(repository, 'node_modules'),
      join(root, 'node_modules'),
      process.platform === 'win32' ? 'junction' : 'dir',
    );
    git('init', '--quiet');
    git('config', 'user.name', 'Formatting test');
    git('config', 'user.email', 'formatting-test@example.invalid');
    git('config', 'commit.gpgsign', 'false');
    git('config', 'core.autocrlf', 'false');
    git('config', 'core.hooksPath', join(root, 'disabled-hooks'));
    const gap = Array.from({ length: 10 }, (_, index) => `// separation ${index}`).join('\n');
    const source = (selected, local) => `${selected}\n${gap}\nexport const local = ${local};\n`;
    const file = join(root, 'frontend/example.ts');
    writeFileSync(file, source('export const selected = 0;', 0));
    git('add', 'frontend/example.ts');
    git('commit', '--quiet', '-m', 'Baseline');

    writeFileSync(file, source('export const selected={x:1};', 0));
    const generated = 'export type Generated={x:string};\n';
    writeFileSync(join(root, 'frontend/src/api/generated.ts'), generated);
    git('add', 'frontend/example.ts', 'frontend/src/api/generated.ts');
    writeFileSync(file, source('export const selected={x:1};', 7));

    execFileSync(process.execPath, [join(root, 'node_modules/husky/bin.js')], { cwd: root, env });
    git('commit', '--quiet', '-m', 'Format staged code');

    assert.equal(
      git('show', 'HEAD:frontend/example.ts'),
      source('export const selected = { x: 1 };', 0),
    );
    assert.equal(readFileSync(file, 'utf8'), source('export const selected = { x: 1 };', 7));
    assert.equal(git('show', 'HEAD:frontend/src/api/generated.ts'), generated);
    assert.equal(git('diff', '--cached'), '');
    assert.match(git('diff', '--', 'frontend/example.ts'), /\+export const local = 7;/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

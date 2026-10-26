import { execFileSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const files = args.filter((arg) => arg !== '--check');
try {
  execFileSync(
    process.execPath,
    [
      resolve(root, 'node_modules/prettier/bin/prettier.cjs'),
      args.includes('--check') ? '--check' : '--write',
      ...(files.length ? files : ['.']),
    ],
    { cwd: resolve(root, 'frontend'), stdio: 'inherit' },
  );
} catch (error) {
  process.exit(error.status || 1);
}

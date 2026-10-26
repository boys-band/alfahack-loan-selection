import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { test } from 'node:test';
import { javaFormattingCommands, runJavaFormatting } from '../format-java.mjs';

test('routes only selected Java files to their own service, including nonstandard test paths', () => {
  const root = '/tmp/project with spaces';
  const commands = javaFormattingCommands(
    [
      `${root}/services/catalog/src/main/java/ru/alfa/catalog/Product.java`,
      `${root}/services/disbursement/tests/integration/ru/alfa/disbursement/Payout Test.java`,
      `${root}/services/catalog/src/test/java/ru/alfa/catalog/ConfigTest.java`,
    ],
    { root },
  );
  assert.deepEqual(
    commands.map(({ cwd, args }) => ({ cwd, args })),
    [
      {
        cwd: resolve(root, 'services/catalog'),
        args: [
          'spotlessApply',
          '--console=plain',
          '-PformatFiles=["src/main/java/ru/alfa/catalog/Product.java","src/test/java/ru/alfa/catalog/ConfigTest.java"]',
        ],
      },
      {
        cwd: resolve(root, 'services/disbursement'),
        args: [
          'spotlessApply',
          '--console=plain',
          '-PformatFiles=["tests/integration/ru/alfa/disbursement/Payout Test.java"]',
        ],
      },
    ],
  );
});

test('rejects files outside owned Java source paths rather than formatting arbitrary files', () => {
  for (const file of [
    '/tmp/other/services/catalog/src/main/java/Other.java',
    '/tmp/project/services/catalog/build/generated/Other.java',
    '/tmp/project/services/unknown/src/main/java/Other.java',
    '/tmp/project/contracts/schemas.yaml',
  ]) {
    assert.throws(
      () => javaFormattingCommands([file], { root: '/tmp/project' }),
      /Unsupported Java source/,
    );
  }
});

test('with no selected files, checks all builds through the root Wrapper', () => {
  const [command] = javaFormattingCommands([], { root: '/tmp/project', check: true });
  assert.equal(command.cwd, '/tmp/project');
  assert.deepEqual(command.args, ['spotlessCheck', '--console=plain']);
});

test(
  'executes the Wrapper with literal filenames and leaves unrelated services untouched',
  { skip: process.platform === 'win32' },
  () => {
    const root = mkdtempSync(join(tmpdir(), 'alfa-format-'));
    try {
      const service = join(root, 'services/catalog');
      mkdirSync(service, { recursive: true });
      writeFileSync(
        join(service, 'gradlew'),
        '#!/bin/sh\nprintf "%s\\n" "$@" > wrapper-args.txt\n',
      );
      chmodSync(join(service, 'gradlew'), 0o755);
      runJavaFormatting([join(service, 'src/main/java/Name $literal.java')], { root });
      assert.equal(
        readFileSync(join(service, 'wrapper-args.txt'), 'utf8'),
        'spotlessApply\n--console=plain\n-PformatFiles=["src/main/java/Name $literal.java"]\n',
      );
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  },
);

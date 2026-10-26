import { execFileSync } from 'node:child_process';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const services = new Set([
  'applications',
  'catalog',
  'verification',
  'decision',
  'agreements',
  'disbursement',
]);

export function javaFormattingCommands(files, { root = repositoryRoot, check = false } = {}) {
  const task = check ? 'spotlessCheck' : 'spotlessApply';
  if (files.length === 0) return [{ cwd: root, args: [task, '--console=plain'] }];

  const groups = new Map();
  for (const file of files) {
    const path = relative(root, resolve(root, file)).split(sep).join('/');
    const match =
      /^services\/([^/]+)\/((?:src\/(?:main|test)\/java|tests\/(?:unit|integration))\/.+\.java)$/.exec(
        path,
      );
    if (isAbsolute(path) || !match || !services.has(match[1])) {
      throw new Error(`Unsupported Java source: ${file}`);
    }
    if (!groups.has(match[1])) groups.set(match[1], new Set());
    groups.get(match[1]).add(match[2]);
  }
  return [...groups].map(([service, paths]) => ({
    cwd: resolve(root, 'services', service),
    args: [task, '--console=plain', `-PformatFiles=${JSON.stringify([...paths].sort())}`],
  }));
}

export function runJavaFormatting(files, options) {
  for (const { cwd, args } of javaFormattingCommands(files, options)) {
    if (process.platform === 'win32') {
      const java = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, 'bin/java.exe') : 'java';
      execFileSync(
        java,
        [
          '-classpath',
          resolve(cwd, 'gradle/wrapper/gradle-wrapper.jar'),
          'org.gradle.wrapper.GradleWrapperMain',
          ...args,
        ],
        { cwd, stdio: 'inherit' },
      );
    } else {
      execFileSync(resolve(cwd, 'gradlew'), args, { cwd, stdio: 'inherit' });
    }
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    runJavaFormatting(
      args.filter((arg) => arg !== '--check'),
      { check: args.includes('--check') },
    );
  } catch (error) {
    console.error(error.message);
    process.exit(error.status || 1);
  }
}

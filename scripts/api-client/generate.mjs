#!/usr/bin/env node
// Generates libs/api-client from the OpenAPI document published by backend/api
// at /v3/api-docs (see openspec/changes/00-bootstrap-monorepo/design.md,
// "Contrato Java -> TypeScript"). Boots the api jar with JDBC/Hibernate/Flyway
// autoconfiguration excluded so no live database is required just to publish
// the OpenAPI document.
//
// Usage:
//   node scripts/api-client/generate.mjs          regenerate libs/api-client in place
//   node scripts/api-client/generate.mjs --check   fail if regeneration would differ
//                                                   from the committed content

import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import { existsSync, mkdtempSync, readFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const repoRoot = path.resolve(import.meta.dirname, '..', '..');
const backendDir = path.join(repoRoot, 'backend');
const apiClientDir = path.join(repoRoot, 'libs', 'api-client');
const apiClientSrcDir = path.join(apiClientDir, 'src');

const isCheck = process.argv.includes('--check');
const isWindows = process.platform === 'win32';
const port = Number(process.env.API_CLIENT_GENERATE_PORT ?? 8099);

const excludedAutoConfiguration = [
  'org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration',
  'org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration',
  'org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration',
].join(',');

// Gradle 9 requires JVM 17+. JAVA_HOME on a developer machine is frequently stale
// (pointing at an older JDK used for unrelated projects), so fall back to this
// repo's known-good local JDK 21 install when the ambient one is too old or
// missing, instead of letting Gradle fail with an opaque version error.
const KNOWN_GOOD_WINDOWS_JDK = 'C:\\Program Files\\Java\\jdk-21';
const GRADLE_MIN_JAVA_MAJOR = 17;

function javaHomeMajorVersion(javaHome) {
  try {
    const releaseFile = path.join(javaHome, 'release');
    if (!existsSync(releaseFile)) return null;
    const match = readFileSync(releaseFile, 'utf8').match(
      /JAVA_VERSION="(\d+)/,
    );
    return match ? Number(match[1]) : null;
  } catch {
    return null;
  }
}

function gradleEnv() {
  const env = { ...process.env };
  const currentMajor = env.JAVA_HOME
    ? javaHomeMajorVersion(env.JAVA_HOME)
    : null;
  const isTooOld =
    currentMajor === null || currentMajor < GRADLE_MIN_JAVA_MAJOR;
  if (isWindows && isTooOld && existsSync(KNOWN_GOOD_WINDOWS_JDK)) {
    env.JAVA_HOME = KNOWN_GOOD_WINDOWS_JDK;
  }
  return env;
}

function javaExecutable(env) {
  if (env.JAVA_HOME) {
    return path.join(env.JAVA_HOME, 'bin', isWindows ? 'java.exe' : 'java');
  }
  return 'java';
}

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'inherit', ...options });
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(
          new Error(`${command} ${args.join(' ')} exited with code ${code}`),
        );
      }
    });
  });
}

// gradlew.bat / npx.cmd need cmd.exe on Windows. With shell:true, Node passes the
// command and args through to cmd.exe verbatim, concatenated with plain spaces and
// without quoting (see child_process docs), so any token containing a space (e.g.
// this workspace's "Mis proyectos" path segment) must be quoted here ourselves.
function quoteForWindowsShell(value) {
  return /\s/.test(value) ? `"${value}"` : value;
}

function runShellCommand(command, args, options = {}) {
  const spawnCommand = isWindows ? quoteForWindowsShell(command) : command;
  const spawnArgs = isWindows ? args.map(quoteForWindowsShell) : args;
  return new Promise((resolve, reject) => {
    const child = spawn(spawnCommand, spawnArgs, {
      stdio: 'inherit',
      shell: isWindows,
      ...options,
    });
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(
          new Error(`${command} ${args.join(' ')} exited with code ${code}`),
        );
      }
    });
  });
}

async function buildApiBootJar(env) {
  const gradlew = isWindows
    ? path.join(backendDir, 'gradlew.bat')
    : path.join(backendDir, 'gradlew');
  await runShellCommand(gradlew, [':api:bootJar', '--console=plain'], {
    cwd: backendDir,
    env,
  });

  const libsDir = path.join(backendDir, 'api', 'build', 'libs');
  const jars = (await fs.readdir(libsDir)).filter(
    (name) => name.endsWith('.jar') && !name.endsWith('-plain.jar'),
  );
  if (jars.length !== 1) {
    throw new Error(
      `Expected exactly one bootable jar in ${libsDir}, found: ${jars.join(', ') || '(none)'}`,
    );
  }
  return path.join(libsDir, jars[0]);
}

async function fetchOpenApiDocument(jarPath, env) {
  const java = javaExecutable(env);
  const child = spawn(
    java,
    [
      '-jar',
      jarPath,
      `--server.port=${port}`,
      '--spring.profiles.active=local',
      `--spring.autoconfigure.exclude=${excludedAutoConfiguration}`,
      '--spring.main.banner-mode=off',
    ],
    { env, stdio: ['ignore', 'pipe', 'pipe'] },
  );

  let stdout = '';
  let stderr = '';
  child.stdout.on('data', (chunk) => {
    stdout += chunk.toString();
  });
  child.stderr.on('data', (chunk) => {
    stderr += chunk.toString();
  });

  const readyMarker = 'Started ApiApplication';
  const startupTimeoutMs = 60_000;

  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(
          new Error(
            `api did not report startup within ${startupTimeoutMs}ms.\n${stdout}\n${stderr}`,
          ),
        );
      }, startupTimeoutMs);

      const check = () => {
        if (stdout.includes(readyMarker)) {
          clearTimeout(timer);
          resolve();
        }
      };
      child.stdout.on('data', check);
      child.on('exit', (code) => {
        clearTimeout(timer);
        reject(
          new Error(
            `api process exited early with code ${code}.\n${stdout}\n${stderr}`,
          ),
        );
      });
      check();
    });

    const response = await fetch(`http://localhost:${port}/v3/api-docs`);
    if (!response.ok) {
      throw new Error(`GET /v3/api-docs returned HTTP ${response.status}`);
    }
    return await response.text();
  } finally {
    child.kill();
  }
}

async function runOpenApiGenerator(specPath, outputDir) {
  await fs.rm(outputDir, { recursive: true, force: true });
  await fs.mkdir(outputDir, { recursive: true });

  // Invoke the CLI's own JS entry point with plain `node` (no shell, no npx.cmd
  // wrapper) so argv reaches it as discrete strings. Going through cmd.exe on
  // Windows (npx.cmd -> openapi-generator-cli.cmd) re-splits quoted paths that
  // contain spaces, such as this workspace's "Mis proyectos" segment.
  const cliEntry = path.join(
    repoRoot,
    'node_modules',
    '@openapitools',
    'openapi-generator-cli',
    'main.js',
  );
  const cliArgs = [
    cliEntry,
    'generate',
    '-i',
    specPath,
    '-g',
    'typescript-angular',
    '-o',
    outputDir,
    '--additional-properties',
    'npmName=@fleetpulse/api-client,ngVersion=21.0.0,supportsES6=true,withInterfaces=true',
  ];

  await run('node', cliArgs, { cwd: repoRoot });
}

async function collectFiles(rootDir) {
  const files = [];
  async function walk(currentDir) {
    const entries = await fs.readdir(currentDir, { withFileTypes: true });
    for (const entry of entries) {
      const fullPath = path.join(currentDir, entry.name);
      if (entry.isDirectory()) {
        await walk(fullPath);
      } else {
        files.push(path.relative(rootDir, fullPath).split(path.sep).join('/'));
      }
    }
  }
  if (existsSync(rootDir)) {
    await walk(rootDir);
  }
  return files.sort();
}

async function diffDirectories(committedDir, freshDir) {
  const [committedFiles, freshFiles] = await Promise.all([
    collectFiles(committedDir),
    collectFiles(freshDir),
  ]);

  const committedSet = new Set(committedFiles);
  const freshSet = new Set(freshFiles);

  const missing = freshFiles.filter((file) => !committedSet.has(file));
  const extra = committedFiles.filter((file) => !freshSet.has(file));
  const changed = [];

  for (const file of freshFiles) {
    if (!committedSet.has(file)) continue;
    const [committedContent, freshContent] = await Promise.all([
      fs.readFile(path.join(committedDir, file), 'utf8'),
      fs.readFile(path.join(freshDir, file), 'utf8'),
    ]);
    if (committedContent !== freshContent) {
      changed.push(file);
    }
  }

  return { missing, extra, changed };
}

async function main() {
  const env = gradleEnv();
  const jarPath = await buildApiBootJar(env);
  const openApiDocument = await fetchOpenApiDocument(jarPath, env);

  const workDir = mkdtempSync(path.join(os.tmpdir(), 'fleetpulse-api-client-'));
  const specPath = path.join(workDir, 'openapi.json');
  await fs.writeFile(specPath, openApiDocument, 'utf8');

  try {
    // Always generate into a temp directory outside the repo, then copy via plain
    // Node fs calls. openapi-generator-cli's own argument parsing mis-splits an -o
    // value that contains a space, which this workspace's path does ("Mis
    // proyectos") -- the same class of Windows path-with-spaces issue already
    // tracked for @nx/gradle (see openspec/changes/00-bootstrap-monorepo/tasks.md).
    const freshOutputDir = path.join(workDir, 'generated');
    await runOpenApiGenerator(specPath, freshOutputDir);

    if (isCheck) {
      const { missing, extra, changed } = await diffDirectories(
        apiClientSrcDir,
        freshOutputDir,
      );
      if (missing.length === 0 && extra.length === 0 && changed.length === 0) {
        console.log(
          'libs/api-client is in sync with the OpenAPI contract published by backend/api.',
        );
        return;
      }

      console.error(
        'libs/api-client is out of sync with the OpenAPI contract published by backend/api.',
      );
      if (missing.length > 0) {
        console.error(
          `Missing from libs/api-client/src (present in a fresh generation):\n  ${missing.join('\n  ')}`,
        );
      }
      if (extra.length > 0) {
        console.error(
          `Present in libs/api-client/src but no longer generated:\n  ${extra.join('\n  ')}`,
        );
      }
      if (changed.length > 0) {
        console.error(
          `Content differs from a fresh generation:\n  ${changed.join('\n  ')}`,
        );
      }
      console.error('Run "npm run generate:api-client" and commit the result.');
      process.exitCode = 1;
      return;
    }

    await fs.rm(apiClientSrcDir, { recursive: true, force: true });
    await fs.cp(freshOutputDir, apiClientSrcDir, { recursive: true });
    console.log(`libs/api-client regenerated from ${jarPath}.`);
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});

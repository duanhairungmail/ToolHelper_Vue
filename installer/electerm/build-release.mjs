import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const lock = JSON.parse(fs.readFileSync(path.join(here, 'source-lock.json'), 'utf8'))
const args = new Map()
for (let i = 2; i < process.argv.length; i += 2) args.set(process.argv[i], process.argv[i + 1])
const root = path.resolve(here, '../..')
const workRoot = path.join(root, 'test-runtime/electerm-build')
fs.mkdirSync(workRoot, { recursive: true })
const work = fs.mkdtempSync(path.join(workRoot, 'release-'))

function run(command, argv, cwd = work, extraEnvironment = {}) {
  const result = spawnSync(command, argv, { cwd, windowsHide: true, stdio: 'inherit', env: { ...process.env, ...extraEnvironment }, timeout: 600_000 })
  if (result.error || result.status !== 0) throw new Error(`Build step failed: ${path.basename(command)} (${result.status})`, { cause: result.error })
}
function sha256(file) { return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex') }
function replaceExactly(file, before, after) {
  const content = fs.readFileSync(file, 'utf8').replaceAll('\r\n', '\n')
  if (!content.includes(before)) throw new Error(`Pinned upstream patch no longer applies: ${file}`)
  fs.writeFileSync(file, content.replace(before, after))
}
async function download(url, destination) {
  if (fs.existsSync(destination)) return
  const response = await fetch(url, { signal: AbortSignal.timeout(180_000), redirect: 'error' })
  if (!response.ok) throw new Error(`Official runtime download failed (${response.status})`)
  fs.writeFileSync(destination, Buffer.from(await response.arrayBuffer()))
}

if (args.has('--generate-development-key')) {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('ed25519')
  fs.writeFileSync(path.join(work, 'development-private.pem'), privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 })
  fs.writeFileSync(path.join(work, 'development-public.pem'), publicKey.export({ type: 'spki', format: 'pem' }))
  console.log(`Development keys (never use as production signing authority): ${work}`)
} else {
  if (process.platform !== 'win32' || process.arch !== 'x64') throw new Error('This pinned build targets Windows X64')
  const source = path.resolve(args.get('--source') || 'D:/DevlopProject/electerm-web')
  const signingKey = args.get('--signing-key')
  if (!signingKey) throw new Error('Supply --signing-key <Ed25519 PKCS8 PEM>; private keys are never bundled')
  const output = path.resolve(args.get('--output') || path.join(work, 'release'))
  fs.mkdirSync(output, { recursive: true })
  const app = path.join(work, 'source')
  fs.mkdirSync(app)
  // 从固定 Git 对象导出，既不复制 .env/数据，也不覆盖用户本地源码中的修改。
  run('git', ['-c', `safe.directory=${source.replaceAll('\\', '/')}`, '-C', source, 'archive', '--format=tar', `--output=${path.join(work, 'source.tar')}`, lock.sourceCommit])
  run('tar', ['-xf', path.join(work, 'source.tar'), '-C', app])
  if (sha256(path.join(app, 'package-lock.json')) !== lock.lockSha256) throw new Error('Source dependency lock hash mismatch')
  if (JSON.parse(fs.readFileSync(path.join(app, 'package.json'))).version !== lock.electermVersion) throw new Error('Source version mismatch')
  const runtimeZip = path.join(workRoot, `node-v${lock.nodeVersion}-${lock.platform}.zip`)
  await download(lock.nodeArchive, runtimeZip)
  if (sha256(runtimeZip) !== lock.nodeArchiveSha256) throw new Error('Official Node runtime SHA-256 mismatch')
  run('tar', ['-xf', runtimeZip, '-C', work])
  const runtime = path.join(work, `node-v${lock.nodeVersion}-${lock.platform}`), node = path.join(runtime, 'node.exe')
  if (sha256(node) !== lock.nodeExecutableSha256) throw new Error('Node executable SHA-256 mismatch')
  const npm = path.join(runtime, 'node_modules/npm/bin/npm-cli.js')
  const buildEnvironment = { PATH: runtime + path.delimiter + process.env.PATH, npm_config_cache: path.join(workRoot, 'npm-cache') }
  // 上游锁未包含可选 React 类型 peer；沿用其依赖树，不解析/升级到新的 peer 版本。
  run(node, [npm, 'ci', '--legacy-peer-deps', '--ignore-scripts', '--no-audit', '--no-fund'], app, buildEnvironment)
  run(node, ['build/bin/install.js'], app, buildEnvironment)
  // 禁止 Electerm 自升级，保留必须由备份保护的数据库 schema 迁移。
  replaceExactly(path.join(app, 'src/client/electerm-react/store/app-upgrade.js'), "refsStatic.get('upgrade')?.appUpdateCheck(isManual)", "return false // ToolHelper owns application updates")
  replaceExactly(path.join(app, 'src/client/electerm-react/store/app-upgrade.js'), "import { refsStatic } from '../components/common/ref'", '// Application update checks are disabled in the managed build.')
  replaceExactly(path.join(app, 'src/app/server/server.js'), "app.use(morgan(\n    ':method :url :status :res[content-length] - :response-time ms'\n  ))", '// ToolHelper intentionally disables URL access logs (WebSocket URLs may carry JWTs).')
  replaceExactly(path.join(app, 'src/app/common/log.js'), 'config()', 'config()\nlog.transports.file.level = false\nlog.transports.console.level = false')
  replaceExactly(path.join(app, 'src/app/lib/npm.js'), 'export async function downloadPackage (packageName, targetFolder) {', "export async function downloadPackage (packageName, targetFolder) {\n  throw new Error('Runtime module installation is disabled; update through ToolHelper')")
  run(node, [path.join(app, 'node_modules/vite/bin/vite.js'), 'build', '--config', './build/vite/conf.js'], app, { ...buildEnvironment, NODE_ENV: 'production' })
  run(node, ['build/bin/copy.js'], app, buildEnvironment)
  // 再次用 ci 按同一锁文件只安装运行依赖；prune --package-lock=false 会重新解析版本。
  run(node, [npm, 'ci', '--legacy-peer-deps', '--omit=dev', '--ignore-scripts', '--no-audit', '--no-fund'], app, buildEnvironment)
  if (sha256(path.join(app, 'package-lock.json')) !== lock.lockSha256) throw new Error('Build modified the upstream dependency lock')
  run(node, ['--input-type=module', '-e', "await import('node-pty'); await import('@electerm/ssh2'); await import('node:sqlite')"], app, buildEnvironment)
  const payload = path.join(work, 'payload')
  fs.mkdirSync(path.join(payload, 'app'), { recursive: true }); fs.mkdirSync(path.join(payload, 'runtime'))
  for (const name of ['src/app', 'dist', 'node_modules', 'package.json', 'package-lock.json', 'LICENSE']) fs.cpSync(path.join(app, name), path.join(payload, 'app', name), { recursive: true, dereference: true })
  fs.copyFileSync(path.join(here, 'toolhelper-entry.mjs'), path.join(payload, 'app/toolhelper-entry.mjs'))
  fs.copyFileSync(node, path.join(payload, 'runtime/node.exe'))
  fs.copyFileSync(path.join(runtime, 'LICENSE'), path.join(payload, 'runtime/LICENSE'))
  fs.writeFileSync(path.join(payload, 'NOTICES.md'), `# ToolHelper managed Electerm Web\n\nElecterm Web ${lock.electermVersion}, source commit ${lock.sourceCommit}, MIT (see app/LICENSE).\nNode.js ${lock.nodeVersion}, see runtime/LICENSE.\nDependencies locked by app/package-lock.json (${lock.lockSha256}); dependency license files remain in app/node_modules.\n\nChanges: local-only authenticated bootstrap; local terminal disabled; application update checks and runtime dependency downloads disabled; URL/file diagnostics disabled; parent process watchdog added.\nDatabase migrations remain enabled and are protected by pre-upgrade backups.\n`)
  const jar = args.get('--jar') || 'jar'
  run(jar, ['--create', '--no-manifest', '--file', path.join(output, 'payload.zip'), '-C', payload, '.'])
  const manifest = { schemaVersion: 1, id: `electerm-${lock.electermVersion}-th${lock.packageRevision}`, version: lock.electermVersion, nodeVersion: lock.nodeVersion, platform: lock.platform, archive: 'payload.zip', sha256: sha256(path.join(output, 'payload.zip')), nodeSha256: lock.nodeExecutableSha256, lockSha256: lock.lockSha256, sourceCommit: lock.sourceCommit }
  const bytes = Buffer.from(JSON.stringify(manifest, null, 2) + '\n')
  const privateKey = crypto.createPrivateKey(fs.readFileSync(path.resolve(signingKey)))
  if (privateKey.asymmetricKeyType !== 'ed25519') throw new Error('Signing key must be Ed25519')
  fs.writeFileSync(path.join(output, 'manifest.json'), bytes)
  fs.writeFileSync(path.join(output, 'manifest.sig'), crypto.sign(null, bytes, privateKey).toString('base64') + '\n')
  const offline = path.join(output, `electerm-${lock.electermVersion}-offline.zip`)
  run(jar, ['--create', '--no-manifest', '--file', offline, '-C', output, 'manifest.json', '-C', output, 'manifest.sig', '-C', output, 'payload.zip'])
  console.log(`Signed release produced: ${output}`)
}

import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '../..')
const lock = JSON.parse(fs.readFileSync(path.join(here, 'source-lock.json')))
const args = new Map()
for (let i = 2; i < process.argv.length; i += 2) args.set(process.argv[i], process.argv[i + 1])
const work = fs.mkdtempSync(path.join(root, 'test-runtime/nodered-build-'))
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
function run(exe, argv, cwd = work, env = {}) {
  const result = spawnSync(exe, argv, { cwd, env: { ...process.env, ...env }, stdio: 'inherit', windowsHide: true, timeout: 600000 })
  if (result.error || result.status !== 0) throw new Error('Build command failed: ' + path.basename(exe), { cause: result.error })
}
function requireHash(file, value) { if (hash(file) !== value) throw new Error('Pinned SHA-256 mismatch: ' + path.basename(file)) }

// 签名私钥只从构建方明确指定的位置读取，绝不自动生成生产信任根。
const keyPath = args.get('--signing-key')
if (!keyPath) throw new Error('Supply --signing-key <Ed25519 private PEM>')
if (process.platform !== 'win32' || process.arch !== 'x64') throw new Error('This build requires Windows X64')
const key = crypto.createPrivateKey(fs.readFileSync(path.resolve(keyPath)))
if (key.asymmetricKeyType !== 'ed25519') throw new Error('Signing key must be Ed25519')
const output = path.resolve(args.get('--output') || path.join(here, 'artifacts'))
fs.mkdirSync(output, { recursive: true })
const runtimeZip = path.resolve(args.get('--node-archive') || path.join(root, `test-runtime/electerm-build/node-v${lock.nodeVersion}-${lock.platform}.zip`))
if (!fs.existsSync(runtimeZip)) {
  const response = await fetch(lock.nodeArchive, { redirect: 'error', signal: AbortSignal.timeout(180000) })
  if (!response.ok) throw new Error('Official Node runtime download failed')
  fs.writeFileSync(runtimeZip, Buffer.from(await response.arrayBuffer()))
}
requireHash(runtimeZip, lock.nodeArchiveSha256)
run('tar.exe', ['-xf', runtimeZip, '-C', work])
const runtime = path.join(work, `node-v${lock.nodeVersion}-${lock.platform}`)
const node = path.join(runtime, 'node.exe')
requireHash(node, lock.nodeExecutableSha256)
const payload = path.join(work, 'payload')
const app = path.join(payload, 'app')
fs.mkdirSync(app, { recursive: true })
const nodeBundle = args.get('--node-bundle')
if (nodeBundle) {
  // 用户显式提供的可审阅预构建节点树，构建过程也不执行其中的安装脚本。
  fs.cpSync(path.resolve(nodeBundle), app, { recursive: true, dereference: false })
  if (!fs.existsSync(path.join(app, 'review.json'))) throw new Error('Node bundle requires reviewed review.json')
} else {
  for (const name of ['package.json', 'package-lock.json']) fs.copyFileSync(path.join(here, name), path.join(app, name))
  requireHash(path.join(app, 'package-lock.json'), lock.lockSha256)
  const env = { PATH: runtime + path.delimiter + process.env.PATH, npm_config_cache: path.join(root, 'test-runtime/nodered-npm-cache') }
  run(node, [path.join(runtime, 'node_modules/npm/bin/npm-cli.js'), 'ci', '--omit=dev', '--ignore-scripts', '--no-audit', '--no-fund'], app, env)
  // 原生预构建二进制应已在包中；不通过下载脚本临时修补。缺失时构建失败，交由发布方构建。
  run(node, ['-e', "require('serialport'); require('node-red-node-serialport/package.json'); require('node-red-contrib-modbus/package.json')"], app, env)
  for (const [name, version] of [['node-red', lock.nodeRedVersion], ['node-red-node-serialport', lock.serialPortVersion], ['node-red-contrib-modbus', lock.modbusVersion]]) {
    if (JSON.parse(fs.readFileSync(path.join(app, 'node_modules', name, 'package.json'))).version !== version) throw new Error('Pinned package version mismatch')
  }
  for (const name of ['settings.cjs', 'coordinator-preload.cjs']) fs.copyFileSync(path.join(here, name), path.join(app, name))
  fs.mkdirSync(path.join(payload, 'runtime'))
  for (const name of ['node.exe', 'LICENSE']) fs.copyFileSync(path.join(runtime, name), path.join(payload, 'runtime', name))
  fs.writeFileSync(path.join(payload, 'NOTICES.md'), `# ToolHelper managed Node-RED\n\nNode-RED ${lock.nodeRedVersion} and node-red-node-serialport ${lock.serialPortVersion}: Apache-2.0.\nnode-red-contrib-modbus ${lock.modbusVersion}: BSD-3-Clause.\nNode.js ${lock.nodeVersion}: see runtime/LICENSE.\nAll transitive dependency notices remain in app/node_modules; exact versions and registry integrity are in app/package-lock.json.\nChanges: authenticated loopback settings, runtime package installation disabled, serial coordinator preload and managed-parent watchdog. Node-RED upstream source files are not modified.\n`)
}
const index = {}
function walk(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const file = path.join(directory, entry.name)
    if (entry.isSymbolicLink()) throw new Error('Payload must not contain links')
    if (entry.isDirectory()) walk(file)
    else index[path.relative(payload, file).replaceAll('\\', '/')] = hash(file)
  }
}
walk(payload)
fs.writeFileSync(path.join(payload, 'files.json'), JSON.stringify(index))
const jar = args.get('--jar') || 'jar'
run(jar, ['--create', '--no-manifest', '--file', path.join(output, 'payload.zip'), '-C', payload, '.'])
const version = nodeBundle ? args.get('--bundle-version') : lock.nodeRedVersion
if (!version || !/^\d+\.\d+\.\d+$/.test(version)) throw new Error('Supply a valid --bundle-version for a node bundle')
const manifest = { schemaVersion: 1, id: `nodered-${nodeBundle ? 'nodes' : 'runtime'}-${version}-r${lock.packageRevision}`, version,
  nodeVersion: lock.nodeVersion, platform: lock.platform, archive: 'payload.zip', sha256: hash(path.join(output, 'payload.zip')),
  nodeSha256: lock.nodeExecutableSha256, lockSha256: hash(path.join(app, 'package-lock.json')), filesSha256: hash(path.join(payload, 'files.json')), kind: nodeBundle ? 'nodes' : 'runtime' }
const bytes = Buffer.from(JSON.stringify(manifest, null, 2) + '\n')
fs.writeFileSync(path.join(output, 'manifest.json'), bytes)
fs.writeFileSync(path.join(output, 'manifest.sig'), crypto.sign(null, bytes, key).toString('base64') + '\n')
run(jar, ['--create', '--no-manifest', '--file', path.join(output, `nodered-${manifest.kind}-${version}-offline.zip`), '-C', output, 'manifest.json', '-C', output, 'manifest.sig', '-C', output, 'payload.zip'])
// 主安装程序仅携带发布清单、签名和公钥，payload始终由受控下载或离线导入提供。
if (args.has('--main-package-metadata')) {
  const metadata = path.resolve(args.get('--main-package-metadata')); fs.mkdirSync(metadata, { recursive: true })
  for (const name of ['manifest.json', 'manifest.sig']) fs.copyFileSync(path.join(output, name), path.join(metadata, name))
  fs.writeFileSync(path.join(metadata, 'release-public.pem'), crypto.createPublicKey(key).export({ type: 'spki', format: 'pem' }))
}
console.log('Signed Node-RED package built: ' + output)

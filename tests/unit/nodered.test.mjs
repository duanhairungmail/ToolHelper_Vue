import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import crypto from 'node:crypto'
import { EventEmitter } from 'node:events'
import ts from 'typescript'

const source = fs.readFileSync('src/api/nodered/client.ts', 'utf8').replace(/^import .*\r?\n/gm, '')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
const { trustedNodeRedUrl } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

test('Node-RED editor URL accepts only the Java-managed loopback port', () => {
  assert.equal(trustedNodeRedUrl('http://127.0.0.1:1880/', 1880), 'http://127.0.0.1:1880/')
  for (const bad of ['https://127.0.0.1:1880/', 'http://localhost:1880/', 'http://127.0.0.1:1881/', 'http://a:b@127.0.0.1:1880/', 'http://127.0.0.1:1880/?token=x', 'http://127.0.0.1:1880/#x', 'http://127.0.0.1:1880/admin']) assert.throws(() => trustedNodeRedUrl(bad, 1880))
})

test('managed settings authenticate credentials and disable package mutation without hiding installed nodes', async () => {
  const salt = crypto.randomBytes(32), password = 'isolated-unit-password', hash = crypto.pbkdf2Sync(password, salt, 210000, 32, 'sha256')
  const context = { module: { exports: {} }, Buffer, process: { env: {
    TOOLHELPER_NODERED_ADMIN_SALT: salt.toString('base64'), TOOLHELPER_NODERED_ADMIN_HASH: hash.toString('base64'),
    TOOLHELPER_NODERED_SECRET: crypto.randomBytes(32).toString('base64'), TOOLHELPER_NODERED_PORT: '1880', TOOLHELPER_NODERED_DATA: 'isolated-data'
  } }, require: name => { assert.equal(name, 'node:crypto'); return crypto } }
  vm.runInNewContext(fs.readFileSync('installer/nodered/settings.cjs', 'utf8'), context)
  const settings = context.module.exports
  assert.equal(settings.uiHost, '127.0.0.1')
  assert.equal((await settings.adminAuth.authenticate('toolhelper', password)).permissions, '*')
  assert.equal(await settings.adminAuth.authenticate('toolhelper', 'wrong'), null)
  assert.equal(await settings.adminAuth.users('anonymous'), null)
  for (const name of ['allowInstall', 'allowUpload', 'allowUpdate']) assert.equal(settings.externalModules.palette[name], false)
  assert.equal(settings.externalModules.palette.denyList, undefined)
  assert.equal(settings.externalModules.autoInstall, false)
  assert.equal(settings.functionExternalModules, false)
  let status, body
  const response = { status: code => { status = code; return response }, json: value => { body = value } }
  settings.httpAdminMiddleware({ method: 'POST', url: '/nodes' }, response, () => assert.fail('package mutation must be blocked'))
  assert.equal(status, 403); assert.equal(body.code, 'TOOLHELPER_NODE_GOVERNANCE_REQUIRED')
  let allowed = false
  settings.httpAdminMiddleware({ method: 'POST', url: '/flows' }, response, () => { allowed = true })
  assert.equal(allowed, true)
})

// 用实际 preload 包装最小串口驱动，只替代边界IO，不启动Node-RED或打开物理设备。
function serialHarness() {
  let nativeOpens = 0, acquired = 0, released = 0, blocked = false, failOpen = false, pendingResolve, holdNative = false, nativeCallback
  class Stream extends EventEmitter {
    constructor() { super(); this.path = 'COM3' }
    open(callback) { nativeOpens++; if (holdNative) nativeCallback = callback; else if (failOpen) callback(new Error('device unavailable')); else callback(null) }
    close(callback) { this.emit('close'); callback?.(null) }
  }
  const module = { _load: () => ({ SerialPortStream: Stream }) }
  const response = (ok, data) => ({ ok, json: async () => ({ success: ok, data, message: 'COM3 被 C# Agent 占用' }) })
  vm.runInNewContext(fs.readFileSync('installer/nodered/coordinator-preload.cjs', 'utf8'), {
    require: name => name === 'node:module' ? module : { spawnSync() { throw new Error('unexpected taskkill') } },
    process: { pid: 123, platform: 'win32', env: { TOOLHELPER_PARENT_PID: '456', TOOLHELPER_SERIAL_API: 'http://127.0.0.1:1', TOOLHELPER_SERIAL_TOKEN: 'unit' } },
    setInterval: () => ({ unref() {} }), AbortSignal,
    fetch: async (_url, options) => {
      if (options.method === 'DELETE') { released++; return response(true) }
      acquired++; if (blocked) return response(false)
      if (pendingResolve) await new Promise(resolve => { pendingResolve = resolve })
      return response(true, { id: String(acquired) })
    }
  })
  module._load('@serialport/stream')
  return { Stream, counts: () => ({ nativeOpens, acquired, released }), block: () => { blocked = true }, fail: () => { failOpen = true }, pause: () => { pendingResolve = true }, resume: () => pendingResolve(), pauseNative: () => { holdNative = true }, finishNative: () => nativeCallback(null) }
}
const settle = () => new Promise(resolve => setImmediate(resolve))
const open = stream => new Promise(resolve => stream.open(resolve))

test('serial conflict blocks native open; successful close releases exactly once', async () => {
  const blocked = serialHarness(); blocked.block()
  assert.match((await open(new blocked.Stream())).message, /C# Agent/)
  assert.equal(blocked.counts().nativeOpens, 0)
  const ready = serialHarness(), stream = new ready.Stream()
  assert.equal(await open(stream), null)
  stream.close(); await settle()
  assert.equal(ready.counts().released, 1)
  assert.equal(await open(stream), null)
  stream.close(); await settle(); assert.equal(ready.counts().released, 2)
})

test('native open failure and close during coordination cannot leak a lease or reopen the port', async () => {
  const failed = serialHarness(); failed.fail(); assert.match((await open(new failed.Stream())).message, /device unavailable/)
  assert.equal(failed.counts().released, 1)
  const cancelled = serialHarness(), stream = new cancelled.Stream(); cancelled.pause()
  const result = open(stream); stream.close(); cancelled.resume()
  assert.match((await result).message, /取消/)
  assert.deepEqual(cancelled.counts(), { nativeOpens: 0, acquired: 1, released: 1 })
  const opening = serialHarness(), port = new opening.Stream(); opening.pauseNative()
  const opened = open(port); await settle()
  let closed = false; port.close(() => { closed = true }); assert.equal(closed, false)
  opening.finishNative(); assert.match((await opened).message, /取消/); await settle()
  assert.equal(closed, true); assert.equal(opening.counts().released, 1)
})

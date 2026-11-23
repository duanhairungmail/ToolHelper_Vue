import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import ts from 'typescript'

// 测试真正的 URL 校验函数；这里只移除不参与校验的网络导入。
const source = fs.readFileSync('src/api/electerm/client.ts', 'utf8').replace(/^import .*\r?\n/gm, '')
const compilerOptions = { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 }
const compiled = ts.transpileModule(source, { compilerOptions }).outputText
const { trustedElectermUrl } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

test('Electerm browser URL must be an exact credential-free loopback origin', () => {
  assert.equal(trustedElectermUrl('http://127.0.0.1:5577/', 5577), 'http://127.0.0.1:5577/')
  for (const bad of [
    'https://cloud.electerm.org/', 'http://localhost:5577/', 'http://127.0.0.1:5578/',
    'http://user:password@127.0.0.1:5577/', 'http://127.0.0.1:5577/?token=secret',
    'http://127.0.0.1:5577/#jwt', 'javascript:alert(1)', 'http://127.0.0.1:5577/api/login'
  ]) assert.throws(() => trustedElectermUrl(bad, 5577))
})

test('API client preserves stable server error codes and diagnostics', async () => {
  const contracts = ts.transpileModule(fs.readFileSync('src/api/contracts.ts', 'utf8'), { compilerOptions }).outputText
  const contractsUrl = `data:text/javascript;base64,${Buffer.from(contracts).toString('base64')}`
  const client = ts.transpileModule(fs.readFileSync('src/api/client.ts', 'utf8'), { compilerOptions }).outputText
    .replace("from './contracts'", `from '${contractsUrl}'`)
    .replace(/import .*from '@\/config\/runtime';?/, "const createTraceId = () => 'unit'; const getRuntimeConfig = () => ({sessionToken: ''});")
  const { request } = await import(`data:text/javascript;base64,${Buffer.from(client).toString('base64')}`)
  const priorWindow = globalThis.window, priorFetch = globalThis.fetch
  globalThis.window = { setTimeout, clearTimeout, location: { origin: 'http://127.0.0.1:5173' } }
  globalThis.fetch = async () => new Response(JSON.stringify({ success: false, code: 'PORT_IN_USE', message: '端口 5577 被占用', traceId: 'unit' }), { status: 409, headers: { 'Content-Type': 'application/json' } })
  try { await assert.rejects(request('http://127.0.0.1:12345/test'), error => error.code === 'PORT_IN_USE' && error.status === 409 && error.message.includes('5577')) }
  finally { globalThis.window = priorWindow; globalThis.fetch = priorFetch }
})

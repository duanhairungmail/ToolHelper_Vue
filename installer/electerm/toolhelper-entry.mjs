// ToolHelper production adapter. Credentials arrive only via the private process environment.
import { spawnSync } from 'node:child_process'
import { join } from 'node:path'
import { readFileSync } from 'node:fs'

const { HOST, PORT, ENABLE_AUTH, DISABLE_LOCAL_TERMINAL, SERVER_PASS, SERVER_SECRET, DB_PATH } = process.env
if (HOST !== '127.0.0.1' || ENABLE_AUTH !== '1' || DISABLE_LOCAL_TERMINAL !== '1' || !DB_PATH || SERVER_PASS?.length < 24 || SERVER_SECRET?.length < 24) {
  throw new Error('ToolHelper requires loopback binding and private Electerm authentication')
}
const parentPid = Number(process.env.TOOLHELPER_PARENT_PID)
if (!Number.isSafeInteger(parentPid) || parentPid < 1 || !process.env.TOOLHELPER_INSTANCE_ID) throw new Error('Missing ToolHelper process owner')
const version = JSON.parse(readFileSync(new URL('./package.json', import.meta.url))).version
const { createApp } = await import('./src/app/server/server.js')
const app = await createApp()
app.get('/toolhelper/health', (_request, response) => {
  response.set('Cache-Control', 'no-store').json({ instanceId: process.env.TOOLHELPER_INSTANCE_ID, version, authenticated: true, localTerminalDisabled: true })
})
app.set('trust proxy', false)
const server = app.listen(Number(PORT), HOST)
server.on('error', () => process.exit(1))

let closing = false
function stop() {
  if (closing) return
  closing = true
  server.close(() => process.exit(0))
  setTimeout(() => process.exit(0), 3000).unref()
}
process.on('SIGTERM', stop)
process.on('SIGINT', stop)
// Java 被强制结束时仍回收本进程树；只按自己的 PID 操作，不按映像名称结束进程。
setInterval(() => {
  try { process.kill(parentPid, 0) }
  catch {
    if (process.platform === 'win32') spawnSync(join(process.env.SystemRoot || 'C:\\Windows', 'System32/taskkill.exe'), ['/PID', String(process.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore', timeout: 4000 })
    stop()
  }
}, 1000).unref()

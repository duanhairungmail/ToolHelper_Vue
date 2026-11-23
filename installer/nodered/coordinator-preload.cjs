const Module = require('node:module')
const { spawnSync } = require('node:child_process')
const nativeLoad = Module._load
const patched = Symbol.for('toolhelper.serial.coordination')
const opening = Symbol('toolhelper.serial.lease')
const leases = new Set()

async function coordinate(method, suffix = '', body) {
  const response = await fetch(process.env.TOOLHELPER_SERIAL_API + suffix, {
    method,
    headers: { Authorization: 'Bearer ' + process.env.TOOLHELPER_SERIAL_TOKEN, Origin: process.env.TOOLHELPER_SERIAL_ORIGIN, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(5000)
  })
  const result = await response.json()
  if (!response.ok || !result.success) throw new Error(result.message || '串口协调服务不可用，已阻止打开串口')
  return result.data
}

// 串口节点和 Modbus RTU 的依赖树可能各有一份 @serialport/stream，必须逐份包装。
Module._load = function (name, parent, isMain) {
  const loaded = nativeLoad.apply(this, arguments)
  if (name === '@serialport/stream' && loaded.SerialPortStream?.prototype && !loaded.SerialPortStream.prototype[patched]) {
    const prototype = loaded.SerialPortStream.prototype
    prototype[patched] = true
    const open = prototype.open
    const close = prototype.close
    prototype.close = function (callback) {
      const state = this[opening]
      if (state && !state.completed) {
        // 关闭与协调/驱动打开并发时等待清理完成，不能在稍后留下已打开的设备。
        state.cancelled = true
        if (callback) state.closeCallbacks.push(callback)
        return
      }
      return close.call(this, callback)
    }
    prototype.open = function (callback) {
      if (this[opening]) { const error = new Error('串口正在打开或已持有租约'); if (callback) callback(error); else this.emit('error', error); return }
      const state = { completed: false, cancelled: false, closeCallbacks: [] }
      this[opening] = state
      const report = error => { if (callback) callback(error); else if (error) this.emit('error', error) }
      coordinate('POST', '', { port: this.path, pid: process.pid }).then(lease => {
        leases.add(lease.id)
        let releasing
        const release = async () => {
          if (!leases.has(lease.id)) return
          if (releasing) return releasing
          // 未确认释放成功时保留租约；父进程死亡后由Java依据PID+启动时间回收。
          releasing = coordinate('DELETE', '/' + lease.id).then(() => {
            leases.delete(lease.id); this[opening] = null; this.removeListener('close', onClose)
            state.closeCallbacks.forEach(callback => callback(null))
          })
          return releasing
        }
        const onClose = () => { void release().catch(() => this.emit('error', new Error('串口租约释放失败，请停止Node-RED后重试'))) }
        const failed = error => { void release().then(() => report(error), () => report(new Error('打开失败且租约释放失败，请停止Node-RED后重试'))) }
        if (state.cancelled) { failed(new Error('串口打开已取消')); return }
        this.once('close', onClose)
        try { open.call(this, error => {
          state.completed = true
          if (error) failed(error)
          else if (state.cancelled) close.call(this, closeError => {
            if (closeError) { state.closeCallbacks.forEach(callback => callback(closeError)); report(closeError) }
            else report(new Error('串口打开已取消'))
          })
          else report(null)
        }) }
        catch (error) { failed(error) }
      }, error => { this[opening] = null; state.closeCallbacks.forEach(callback => callback(null)); report(error) })
    }
  }
  return loaded
}

// Java 被强制结束时不继续运行定时流程；taskkill 仅作用于本进程及其后代，绝不按映像名终止。
let ownerWasReachable = true
const parent = Number(process.env.TOOLHELPER_PARENT_PID)
if (!Number.isSafeInteger(parent) || parent <= 0 || !process.env.TOOLHELPER_SERIAL_API) throw new Error('Managed parent configuration required')
setInterval(() => {
  try { process.kill(parent, 0) }
  catch {
    if (!ownerWasReachable) return
    ownerWasReachable = false
    if (process.platform === 'win32') spawnSync(process.env.SystemRoot + '/System32/taskkill.exe', ['/PID', String(process.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' })
    process.exit(1)
  }
}, 500).unref()

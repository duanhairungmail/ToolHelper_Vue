// ToolHelper managed settings. 部署端不接受网页传入的 settings、host 或安装命令。
const crypto = require('node:crypto')
const required = name => { if (!process.env[name]) throw new Error('ToolHelper runtime environment is incomplete'); return process.env[name] }
const salt = required('TOOLHELPER_NODERED_ADMIN_SALT')
const hash = Buffer.from(required('TOOLHELPER_NODERED_ADMIN_HASH'), 'base64')
const secret = required('TOOLHELPER_NODERED_SECRET')
if (Buffer.from(secret, 'base64').length !== 32 || hash.length !== 32) throw new Error('Invalid protected credentials')

module.exports = {
  uiHost: '127.0.0.1',
  uiPort: Number(required('TOOLHELPER_NODERED_PORT')),
  userDir: required('TOOLHELPER_NODERED_DATA'),
  flowFile: 'flows.json',
  credentialSecret: secret,
  httpAdminMiddleware: (req, res, next) => {
    // 安装、更新、删除节点统一经过Java审阅链路，阻断直接调用上游管理API。
    if (['POST', 'PUT', 'DELETE'].includes(req.method) && /^\/nodes(?:\/|$|\?)/.test(req.url)) {
      return res.status(403).json({ code: 'TOOLHELPER_NODE_GOVERNANCE_REQUIRED', message: 'Manage node packages through ToolHelper approval' })
    }
    if (req.url !== '/toolhelper/health') return next()
    res.json({ instanceId: process.env.TOOLHELPER_INSTANCE_ID, version: require('node-red/package.json').version, authenticated: true, paletteDisabled: true })
  },
  adminAuth: {
    type: 'credentials',
    sessionExpiryTime: 28800,
    users: username => Promise.resolve(username === 'toolhelper' ? { username, permissions: '*' } : null),
    authenticate: (username, password) => {
      const actual = crypto.pbkdf2Sync(password, Buffer.from(salt, 'base64'), 210000, 32, 'sha256')
      return Promise.resolve(username === 'toolhelper' && crypto.timingSafeEqual(actual, hash) ? { username, permissions: '*' } : null)
    }
  },
  // 函数节点不得动态解析 npm 包，Palette 仅显示已受控安装的节点。
  functionExternalModules: false,
  // Palette 的 denyList 同时影响已安装节点的加载，因此只关安装/更新入口。
  externalModules: { autoInstall: false, palette: { allowInstall: false, allowUpload: false, allowUpdate: false }, modules: { allowInstall: false, allowList: [], denyList: ['*'] } },
  editorTheme: { projects: { enabled: false }, palette: { editable: false }, tours: false },
  runtimeState: { enabled: true, ui: true },
  logging: { console: { level: 'off', metrics: false, audit: false } },
  diagnostics: { enabled: false, ui: false }
}

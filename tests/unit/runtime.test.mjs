import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

test('runtime config contract keeps production ports out of source defaults', () => {
  const source = fs.readFileSync('src/config/runtime.ts', 'utf8')
  assert.match(source, /javaApiBase: ''/)
  assert.match(source, /localApiBase: ''/)
  assert.doesNotMatch(source, /localhost:\\d{4}/)
})

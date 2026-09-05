import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

test('OpenAPI contract exposes both health endpoints and replayable SSE', () => {
  const contract = JSON.parse(fs.readFileSync('contracts/openapi/openapi.json', 'utf8'))
  assert.ok(contract.paths['/actuator/health'])
  assert.ok(contract.paths['/health'])
  assert.ok(contract.paths['/api/jobs/{jobId}/events'])
  assert.deepEqual(contract.paths['/api/jobs/{jobId}/events'].get.parameters[1].schema, { type: 'string' })
})

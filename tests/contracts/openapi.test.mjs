import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

test('OpenAPI contract exposes both health endpoints and replayable SSE', () => {
  const contract = JSON.parse(fs.readFileSync('contracts/openapi/openapi.json', 'utf8'))
  assert.ok(contract.paths['/actuator/health'])
  assert.ok(contract.paths['/health'])
  assert.ok(contract.paths['/api/jobs/{jobId}/events'])
  assert.ok(contract.paths['/api/java/crypto/aes/encrypt'])
  assert.ok(contract.paths['/api/java/crypto/aes/decrypt'])
  assert.deepEqual(contract.paths['/api/jobs/{jobId}/events'].get.parameters[1].schema, { type: 'string' })
})

test('Electerm lifecycle contract includes signed offline import and explicit purge confirmation', () => {
  const contract = JSON.parse(fs.readFileSync('contracts/openapi/openapi.json', 'utf8'))
  for (const action of ['status', 'install', 'start', 'stop', 'restart', 'logs', 'uninstall', 'purge-confirmation']) {
    assert.ok(contract.paths[`/api/integrations/electerm/${action}`], action)
  }
  assert.ok(contract.paths['/api/integrations/electerm/install'].post.requestBody.content['multipart/form-data'])
  assert.equal(contract.paths['/api/integrations/electerm/uninstall'].post.requestBody.content['application/json'].schema.properties.purgeData.default, false)
  assert.deepEqual(contract.components.schemas.ElectermStatus.properties.state.enum, ['NOT_INSTALLED', 'STOPPED', 'STARTING', 'RUNNING'])
})

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

test('Node-RED contract separates review approval and confirmed execution', () => {
  const contract = JSON.parse(fs.readFileSync('contracts/openapi/openapi.json', 'utf8'))
  for (const action of ['status', 'install', 'updates', 'start', 'stop', 'restart', 'runtime', 'events', 'backups', 'restore', 'nodes/review', 'nodes/{id}/approve', 'nodes/{id}/install', 'nodes/{id}']) assert.ok(contract.paths[`/api/integrations/nodered/${action}`], action)
  const body = contract.components.requestBodies.NodeRedInstall.content
  assert.equal(body['application/json'].schema.properties.confirmed.const, true)
  assert.ok(body['multipart/form-data'])
  assert.equal(contract.paths['/api/integrations/nodered/nodes/{id}/install'].post.requestBody.content['application/json'].schema.properties.confirmed.const, true)
  assert.ok(contract.paths['/api/internal/v1/serial/leases'].post)
  assert.ok(contract.paths['/api/local/serial/reservations'].post)
})

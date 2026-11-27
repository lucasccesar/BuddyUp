import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { copyFileSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const image = 'tufin/oasdiff@sha256:cb7cd2acde680a724c9d08c27bc687c83628c02c5869ead033b857935fb4e9f4'
const baseRef = process.argv[2] ?? 'origin/main'
const directory = mkdtempSync(join(tmpdir(), 'buddyup-openapi-'))
const fixture = {
  openapi: '3.1.0',
  info: { title: 'Compatibility fixture', version: '1.0.0' },
  paths: { '/fixture': { get: { responses: { 200: { description: 'OK' } } } } },
}

function compare(base, revision, options = []) {
  const diff = spawnSync('docker', [
    'run', '--rm', '--network', 'none', '--mount', `type=bind,source=${directory},target=/specs,readonly`, image,
    'breaking', '--allow-external-refs=false', '--fail-on', 'ERR', ...options, `/specs/${base}`, `/specs/${revision}`,
  ], { encoding: 'utf8' })
  if (diff.error) throw diff.error
  return diff
}

try {
  writeFileSync(join(directory, 'fixture.json'), JSON.stringify(fixture))
  writeFileSync(join(directory, 'removed.json'), JSON.stringify({ ...fixture, paths: {} }))
  const same = compare('fixture.json', 'fixture.json')
  assert.equal(same.status, 0, same.stderr || same.stdout)
  const incompatible = compare('fixture.json', 'removed.json')
  assert.equal(incompatible.status, 1, incompatible.stderr || incompatible.stdout)
  assert.match(incompatible.stdout, /api-path-removed/)
  console.log('Compatibilidade: remover uma operação foi detectado como alteração incompatível.')

  const base = spawnSync('git', ['show', `${baseRef}:api/openapi.yaml`], { encoding: 'utf8' })
  const exists = spawnSync('git', ['rev-parse', '--verify', `${baseRef}^{commit}`], { encoding: 'utf8' })
  assert.equal(exists.status, 0, exists.stderr)
  if (base.status === 0) {
    writeFileSync(join(directory, 'base.yaml'), base.stdout)
    copyFileSync('openapi.yaml', join(directory, 'revision.yaml'))
    const baseBlob = spawnSync('git', ['rev-parse', `${baseRef}:api/openapi.yaml`], { encoding: 'utf8' })
    assert.equal(baseBlob.status, 0, baseBlob.stderr)
    const legacySessionContract = baseBlob.stdout.trim() === 'fde2a33d422961e1afb0fb0024c4bc65a22910e5'
    const options = []
    if (legacySessionContract) {
      writeFileSync(join(directory, 'legacy-session.ignore'), [
        'POST /v1/interactions/{interactionId}/accept the endpoint no longer allows anonymous access and now only accepts `csrfToken AND webSession, nativeSession`',
        '',
      ].join('\n'))
      options.push('--err-ignore', '/specs/legacy-session.ignore')
    }
    const diff = compare('base.yaml', 'revision.yaml', options)
    process.stdout.write(diff.stdout)
    process.stderr.write(diff.stderr)
    assert.equal(diff.status, 0, 'O diff OpenAPI falhou.')
  } else {
    const path = spawnSync('git', ['ls-tree', '--name-only', baseRef, ':(top)api/openapi.yaml'], { encoding: 'utf8' })
    assert.equal(path.status, 0, path.stderr)
    assert.equal(path.stdout.trim(), '', base.stderr)
    console.log('A branch base ainda não tem contrato OpenAPI. A validação começa neste PR.')
  }
} finally {
  rmSync(directory, { recursive: true })
}

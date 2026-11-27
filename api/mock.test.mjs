import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { once } from 'node:events'
import { createServer } from 'node:net'
import { dirname, resolve } from 'node:path'
import { setTimeout as pause } from 'node:timers/promises'
import { after, before, test } from 'node:test'
import { listGames } from './client.ts'

const require = createRequire(import.meta.url)
const prismPackage = require.resolve('@stoplight/prism-cli/package.json')
const prism = resolve(dirname(prismPackage), require(prismPackage).bin.prism)
const origin = 'http://localhost:5173'
let baseUrl
let server

before(async () => {
  const socket = createServer().listen(0, '127.0.0.1')
  await once(socket, 'listening')
  const { port } = socket.address()
  await new Promise((resolve) => socket.close(resolve))
  baseUrl = `http://127.0.0.1:${port}`
  let logs = ''
  server = spawn(process.execPath, [prism, 'mock', '--errors', '--port', String(port), 'openapi.yaml'])
  for (const stream of [server.stdout, server.stderr]) {
    stream.setEncoding('utf8').on('data', (data) => { logs += data })
  }
  for (let attempt = 0; attempt < 60 && server.exitCode === null; attempt += 1) {
    try {
      await fetch(baseUrl, { signal: AbortSignal.timeout(500) })
      return
    } catch {
      await pause(100)
    }
  }
  assert.fail(`O mock não iniciou.\n${logs}`)
})

after(async () => {
  if (server?.exitCode === null && server.signalCode === null) {
    const stopped = once(server, 'exit')
    server.kill()
    await stopped
  }
})

test('allows web requests from the Vite origin', async () => {
  const page = await listGames(baseUrl, { limit: 1 }, async (url, options) => {
    const headers = new Headers(options.headers)
    headers.set('Origin', origin)
    const response = await fetch(url, { ...options, headers })
    assert.ok([origin, '*'].includes(response.headers.get('access-control-allow-origin')))
    return response
  })
  assert.equal(page.items.length, 1)
  assert.equal(page.nextCursor, null)
})

test('returns the same catalog contract to a native client', async () => {
  const page = await listGames(baseUrl, { limit: 1 })
  assert.equal(page.items[0].name, 'VALORANT')
  assert.equal(page.nextCursor, null)
})

test('returns a documented conflict for an accept command', async () => {
  const response = await fetch(`${baseUrl}/v1/interactions/346da57c-ddca-4ed8-844e-3632de47f125/accept`, {
    method: 'POST',
    headers: { Prefer: 'code=409', Authorization: 'Bearer example-native-session' },
  })
  assert.equal(response.status, 409)
  assert.match(response.headers.get('content-type'), /^application\/problem\+json/)
  assert.equal((await response.json()).code, 'SLOT_UNAVAILABLE')
})

test('documents the response when a session has been revoked', async () => {
  await assertProblem('/v1/auth/session', {
    headers: { Authorization: 'Bearer example-revoked-session' },
  }, 401, 'SESSION_REVOKED')
})

test('documents the conflict when a provider identity belongs to another account', async () => {
  await assertProblem('/v1/accounts/me/login-identities/google', {
    method: 'PUT',
    headers: { Authorization: 'Bearer example-native-session' },
    body: { providerProof: 'example-provider-proof', reauthenticationToken: 'example-reauthentication' },
  }, 409, 'IDENTITY_ALREADY_LINKED')
})

test('requires account proof when the Google email is externally managed', async () => {
  await assertProblem('/v1/auth/social/google/login', {
    method: 'POST',
    body: { providerProof: 'example-provider-proof', client: 'MOBILE', rememberMe: true },
  }, 409, 'ACCOUNT_PROOF_REQUIRED')
})

test('documents the forbidden response for a role change without owner permission', async () => {
  await assertProblem('/v1/admin/accounts/346da57c-ddca-4ed8-844e-3632de47f125/roles', {
    method: 'PUT',
    headers: { Cookie: '__Host-buddyup_session=example-staff-session', 'X-CSRF-Token': 'example-csrf-token' },
    body: { roles: ['MODERATION'], reason: 'Atualização da equipe', reauthenticationToken: 'example-reauthentication' },
  }, 403, 'ROLE_FORBIDDEN')
})

test('documents web login without a bearer credential in the response body', async () => {
  const result = await passwordLoginExample({ client: 'WEB' }, 'WEB_SESSION')
  assert.equal(result.session.rememberMe, false)
  assert.ok(result.session.csrfToken)
  assert.ok(!Object.hasOwn(result, 'sessionToken'))
})

test('documents native login with a persistent-session preference', async () => {
  const result = await passwordLoginExample({ client: 'MOBILE', rememberMe: true }, 'REMEMBERED_NATIVE_SESSION')
  assert.equal(result.session.rememberMe, true)
  assert.ok(result.sessionToken)
  assert.ok(!Object.hasOwn(result.session, 'csrfToken'))
})

async function passwordLoginExample(intent, example) {
  const response = await fetch(`${baseUrl}/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Prefer: `example=${example}` },
    body: JSON.stringify({ email: 'player@example.com', password: 'example-password', ...intent }),
  })

  assert.equal(response.status, 200)
  return response.json()
}

async function assertProblem(path, request, status, code) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...request,
    headers: {
      ...request.headers,
      Prefer: `code=${status}, example=${code}`,
      ...(request.body && { 'Content-Type': 'application/json' }),
    },
    body: request.body && JSON.stringify(request.body),
  })

  assert.equal(response.status, status)
  assert.match(response.headers.get('content-type'), /^application\/problem\+json/)
  assert.equal((await response.json()).code, code)
}

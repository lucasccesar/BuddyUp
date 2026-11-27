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
    headers: { Prefer: 'code=409' },
  })
  assert.equal(response.status, 409)
  assert.match(response.headers.get('content-type'), /^application\/problem\+json/)
  assert.equal((await response.json()).code, 'SLOT_UNAVAILABLE')
})

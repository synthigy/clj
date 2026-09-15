// Real-browser verification harness for the CLJS SDK's watch/mux — NOT a
// shipped example, a repeatable dev check. shadow-cljs's :node-test target
// proves the code RUNS under Node, but Node has no CORS enforcement and a
// different fetch/stream implementation, so it can't prove the browser
// story (fetch-streaming SSE, real cross-origin behavior). This drives the
// compiled :browser-demo build in a real headless Chrome instead.
//
// Prerequisites:
//   npx shadow-cljs compile browser-demo   (compiles examples/browser-demo/out)
//   npm install --no-save playwright-core  (one-time; NOT a project dependency)
//   a live Synthigy server with an OAuth client that has the
//   client_credentials grant (SDK auth is otherwise consumer's concern —
//   see README.md's "Auth in the browser" section; this harness only needs
//   ANY valid bearer token to exercise the wire, so client_credentials is
//   the simplest thing to script here, NOT a statement about how a real
//   browser app should authenticate)
//
// Run: SYNTHIGY_TEST_CLIENT_ID=... SYNTHIGY_TEST_CLIENT_SECRET=... node run.mjs
import { chromium } from 'playwright-core'
import http from 'node:http'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const DIR = path.dirname(fileURLToPath(import.meta.url))
const ENDPOINT = process.env.SYNTHIGY_TEST_ENDPOINT || 'http://localhost:7887'
const CLIENT_ID = process.env.SYNTHIGY_TEST_CLIENT_ID
const CLIENT_SECRET = process.env.SYNTHIGY_TEST_CLIENT_SECRET
const DEMO_PORT = 8934

if (!CLIENT_ID || !CLIENT_SECRET) {
  console.error(
    'Set SYNTHIGY_TEST_CLIENT_ID / SYNTHIGY_TEST_CLIENT_SECRET to a client-credentials-capable OAuth client.',
  )
  process.exit(1)
}

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.map': 'application/json' }

function serveDemoDir() {
  return new Promise((resolve) => {
    const server = http.createServer(async (req, res) => {
      try {
        const file = req.url === '/' ? '/index.html' : req.url.split('?')[0]
        const body = await readFile(path.join(DIR, file))
        res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' })
        res.end(body)
      } catch {
        res.writeHead(404)
        res.end('not found')
      }
    })
    server.listen(DEMO_PORT, () => resolve(server))
  })
}

async function mintToken() {
  const res = await fetch(`${ENDPOINT}/oauth/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `grant_type=client_credentials&client_id=${CLIENT_ID}&client_secret=${CLIENT_SECRET}`,
  })
  const body = await res.json()
  if (!body.access_token) throw new Error(`token mint failed: ${JSON.stringify(body)}`)
  return body.access_token
}

async function fetchOneTrack(token) {
  const res = await fetch(`${ENDPOINT}/data`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      operations: [
        { op: 'search', entity: 'music_track', args: { _limit: 1 }, selections: { xid: null, title: null } },
      ],
    }),
  })
  const { results } = await res.json()
  const [row] = results?.[0]?.data ?? []
  if (!row) throw new Error('no music_track row found — is the demo dataset seeded?')
  return row
}

async function main() {
  const token = await mintToken()
  const track = await fetchOneTrack(token)
  const server = await serveDemoDir()

  const url =
    `http://localhost:${DEMO_PORT}/index.html` +
    `?endpoint=${encodeURIComponent(ENDPOINT)}&token=${encodeURIComponent(token)}` +
    `&xid=${track.xid}&title=${encodeURIComponent(track.title)}`

  const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', headless: true })
  const page = await browser.newPage()
  const consoleLines = []
  page.on('console', (msg) => consoleLines.push(`[console.${msg.type()}] ${msg.text()}`))
  page.on('pageerror', (err) => consoleLines.push(`[pageerror] ${err.message}`))

  await page.goto(url)
  try {
    await page.waitForFunction(
      () => document.getElementById('test-result')?.textContent !== 'RUNNING',
      { timeout: 12000 },
    )
  } catch (e) {
    consoleLines.push(`[driver] waitForFunction timed out: ${e.message}`)
  }

  const result = await page.textContent('#test-result')
  console.log('status:', await page.textContent('#status'))
  console.log('search-result:', await page.textContent('#search-result'))
  console.log('live-title:', await page.textContent('#live-title'))
  console.log('test-result:', result)
  console.log('--- browser console ---')
  for (const line of consoleLines) console.log(line)

  await browser.close()
  server.close()
  process.exit(result && result.startsWith('PASS') ? 0 : 1)
}

main().catch((e) => {
  console.error('driver crashed:', e)
  process.exit(1)
})

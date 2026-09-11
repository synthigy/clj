// Real-browser verification driver for :wire-format :transit — see
// transit_check.cljs. Mints a token, serves the compiled page, drives it in
// real headless Chrome, reads #test-result.
import { chromium } from 'playwright-core'
import http from 'node:http'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const DIR = path.dirname(fileURLToPath(import.meta.url))
const ENDPOINT = process.env.SYNTHIGY_TEST_ENDPOINT || 'http://localhost:7887'
const CLIENT_ID = process.env.SYNTHIGY_TEST_CLIENT_ID
const CLIENT_SECRET = process.env.SYNTHIGY_TEST_CLIENT_SECRET
const PORT = 8935

if (!CLIENT_ID || !CLIENT_SECRET) {
  console.error('Set SYNTHIGY_TEST_CLIENT_ID / SYNTHIGY_TEST_CLIENT_SECRET.')
  process.exit(1)
}

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.map': 'application/json' }

function serveDemoDir() {
  return new Promise((resolve) => {
    const server = http.createServer(async (req, res) => {
      try {
        const file = req.url === '/' ? '/transit.html' : req.url.split('?')[0]
        const body = await readFile(path.join(DIR, file))
        res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' })
        res.end(body)
      } catch {
        res.writeHead(404)
        res.end('not found')
      }
    })
    server.listen(PORT, () => resolve(server))
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

async function main() {
  const token = await mintToken()
  const server = await serveDemoDir()
  const url = `http://localhost:${PORT}/transit.html?endpoint=${encodeURIComponent(ENDPOINT)}&token=${encodeURIComponent(token)}`

  const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', headless: true })
  const page = await browser.newPage()
  const consoleLines = []
  const dataRequests = []
  page.on('console', (msg) => consoleLines.push(`[console.${msg.type()}] ${msg.text()}`))
  page.on('pageerror', (err) => consoleLines.push(`[pageerror] ${err.message}`))
  page.on('requestfailed', (req) => consoleLines.push(`[requestfailed] ${req.url()} ${req.failure()?.errorText}`))
  page.on('request', (req) => {
    if (req.url().endsWith('/data')) {
      dataRequests.push({
        contentType: req.headers()['content-type'],
        accept: req.headers()['accept'],
        bodyPrefix: req.postData()?.slice(0, 60),
      })
    }
  })

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
  console.log('json-value:', await page.textContent('#json-value'))
  console.log('transit-value:', await page.textContent('#transit-value'))
  console.log('test-result:', result)
  console.log('--- /data requests observed on the wire ---')
  for (const r of dataRequests) console.log(JSON.stringify(r))
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

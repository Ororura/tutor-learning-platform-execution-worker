// Only teacher-owned checks run in Node. Student JavaScript runs inside the isolated browser.
const fs = require('node:fs');
const assert = require('node:assert/strict');
if (fs.existsSync('/opt/web/browsers')) process.env.PLAYWRIGHT_BROWSERS_PATH = '/opt/web/browsers';
const { chromium } = require('playwright');

async function run(source, check, options = {}) {
  const browser = await chromium.launch({
    headless: true,
    args: ['--disable-dev-shm-usage', '--disable-gpu', '--no-zygote'],
    ...options,
  }).catch(error => { error.infrastructure = true; throw error; });
  try {
    const context = await browser.newContext({ viewport: { width: 1024, height: 768 }, serviceWorkers: 'block' });
    const policy = "default-src 'none'; script-src https://exercise.invalid 'unsafe-inline'; style-src https://exercise.invalid 'unsafe-inline'; img-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'";
    await context.route('**/*', async route => {
      const request = route.request();
      const url = new URL(request.url());
      if (url.origin !== 'https://exercise.invalid' || request.method() !== 'GET') return route.abort();
      const headers = { 'Content-Security-Policy': policy, 'X-Content-Type-Options': 'nosniff' };
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', headers,
        body: `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><link rel="stylesheet" href="/styles.css"></head><body>${source.html}<script src="/script.js"></script></body></html>` });
      if (url.pathname === '/styles.css') return route.fulfill({ contentType: 'text/css', headers, body: source.css });
      if (url.pathname === '/script.js') return route.fulfill({ contentType: 'application/javascript', headers, body: source.javascript });
      return route.abort();
    });
    const page = await context.newPage();
    page.setDefaultTimeout(1500);
    await page.goto('https://exercise.invalid/', { waitUntil: 'load', timeout: 5000 }).catch(error => {
      if (error.name === 'TimeoutError') error.executionTimeout = true;
      throw error;
    });
    const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
    await new AsyncFunction('page', 'assert', check.script)(page, assert);
  } finally {
    await browser.close();
  }
}

if (require.main === module) {
  (async () => {
    const source = JSON.parse(fs.readFileSync('/workspace/solution.json', 'utf8'));
    const check = JSON.parse(fs.readFileSync(0, 'utf8'));
    try {
      await run(source, check);
      process.stdout.write('PASS');
    } catch (error) {
      if (error.infrastructure || error.executionTimeout) {
        process.exitCode = error.infrastructure ? 125 : 124;
        return;
      }
      process.stdout.write('FAIL');
      process.stderr.write(`${String(check.label || 'Проверка').slice(0, 200)}: ${String(error.message).slice(0, 1500)}`);
    }
  })().catch(() => { process.stderr.write('Unable to start web checker'); process.exitCode = 1; });
}
module.exports = { run };

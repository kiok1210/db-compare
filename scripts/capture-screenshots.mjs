/**
 * Capture UI screenshots for docs/manual (requires app on :8080).
 * Usage: node scripts/capture-screenshots.mjs
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const outDir = path.join(root, 'docs', 'manual', 'images');
const base = process.env.BASE_URL || 'http://localhost:8080';
const user = process.env.DBCOMPARE_USER || 'admin';
const pass = process.env.DBCOMPARE_PASS || 'DbC0mpare#2026!Xk9$mQ7';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const chrome =
  process.env.CHROME_PATH ||
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

fs.mkdirSync(outDir, { recursive: true });

const shots = [
  { name: '01-login', path: '/login', beforeLogin: true },
  { name: '02-home', path: '/' },
  { name: '03-datasource-list', path: '/datasource/list' },
  { name: '04-datasource-form', path: '/datasource/add' },
  { name: '05-compare-config', path: '/compare/config' },
  { name: '06-sync-list', path: '/sync/list' },
  { name: '07-sync-config', path: '/sync/config' },
  { name: '08-report-list', path: '/report/list' },
  { name: '09-viewer', path: '/viewer' },
  { name: '10-settings', path: '/settings' },
  { name: '14-cleanup', path: '/cleanup' },
];

async function shot(page, name) {
  const file = path.join(outDir, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  console.log('wrote', path.relative(root, file));
}

const browser = await puppeteer.launch({
  executablePath: chrome,
  headless: 'new',
  defaultViewport: { width: 1440, height: 900 },
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});

try {
  const page = await browser.newPage();

  // Login page (unauthenticated)
  await page.goto(`${base}/login`, { waitUntil: 'networkidle2', timeout: 60000 });
  await shot(page, '01-login');

  // Form login
  await page.type('#username', user, { delay: 10 });
  await page.type('#password', pass, { delay: 10 });
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 60000 }),
    page.click('button[type="submit"]'),
  ]);

  for (const s of shots) {
    if (s.beforeLogin) continue;
    await page.goto(`${base}${s.path}`, { waitUntil: 'networkidle2', timeout: 60000 });
    await sleep(600);
    await shot(page, s.name);
  }

  // Prefer an existing compare/sync report if present
  const reportCandidates = [
    path.join(root, 'reports'),
  ];
  let compareReportUrl = null;
  let syncReportUrl = null;
  function walk(dir) {
    if (!fs.existsSync(dir)) return;
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, ent.name);
      if (ent.isDirectory()) walk(p);
      else if (ent.name === 'report.html') {
        const rel = path.relative(path.join(root, 'reports'), p).replace(/\\/g, '/');
        if (rel.startsWith('sync/') && !syncReportUrl) {
          const id = rel.split('/')[1];
          syncReportUrl = `${base}/sync/report/${id}`;
        } else if (!rel.startsWith('sync/') && !compareReportUrl) {
          const id = rel.split('/')[0];
          compareReportUrl = `${base}/compare/report/${id}`;
        }
      }
    }
  }
  walk(path.join(root, 'reports'));

  if (compareReportUrl) {
    await page.goto(compareReportUrl, { waitUntil: 'networkidle2', timeout: 60000 });
    await sleep(800);
    await shot(page, '11-compare-report');
  } else {
    console.log('skip compare report (none found)');
  }
  if (syncReportUrl) {
    await page.goto(syncReportUrl, { waitUntil: 'networkidle2', timeout: 60000 });
    await sleep(800);
    await shot(page, '12-sync-report');
  } else {
    console.log('skip sync report (none found)');
  }

  // Sync confirm: open first PENDING edit link if any
  await page.goto(`${base}/sync/list`, { waitUntil: 'networkidle2', timeout: 60000 });
  const editHref = await page.evaluate(() => {
    const a = [...document.querySelectorAll('a')].find(
      (el) => /编辑|确认|开始/.test(el.textContent || '') && /\/sync\//.test(el.getAttribute('href') || '')
    );
    return a ? a.getAttribute('href') : null;
  });
  if (editHref) {
    await page.goto(new URL(editHref, base).toString(), { waitUntil: 'networkidle2', timeout: 60000 });
    await sleep(800);
    await shot(page, '13-sync-confirm');
  } else {
    console.log('skip sync confirm (no draft link)');
  }
} finally {
  await browser.close();
}

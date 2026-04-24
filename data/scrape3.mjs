/**
 * Round 3:
 * 1. Visit parks.org.il/files/הורדת-מידע-גאוגרפי/ with stealth to find download links.
 * 2. On govmap.gov.il/?lay=150, click inside a known reserve to trigger entitiesByPoint,
 *    and capture the API response (to understand the feature schema and any download option).
 * 3. Check if there's a WPS or GetFeatureInfo that can yield polygon geometry.
 */

import { chromium } from 'playwright';
import https from 'https';
import { createWriteStream } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const STEALTH_CTX = {
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
  locale: 'he-IL',
  viewport: { width: 1440, height: 900 },
  extraHTTPHeaders: {
    'Accept-Language': 'he-IL,he;q=0.9,en-US;q=0.8,en;q=0.7',
    'sec-ch-ua': '"Chromium";v="124", "Google Chrome";v="124", "Not-A.Brand";v="99"',
    'sec-ch-ua-mobile': '?0',
    'sec-ch-ua-platform': '"macOS"',
  },
};

async function makeCtx(browser) {
  const ctx = await browser.newContext(STEALTH_CTX);
  await ctx.addInitScript(() => {
    Object.defineProperty(navigator, 'webdriver', { get: () => false });
    Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3] });
    Object.defineProperty(navigator, 'languages', { get: () => ['he-IL', 'he', 'en-US'] });
  });
  return ctx;
}

// ── 1. Harvest download page ──────────────────────────────────────────
async function harvestDownloadPage(browser) {
  console.log('\n══════ parks.org.il downloads page ══════');
  const ctx = await makeCtx(browser);
  const page = await ctx.newPage();

  const downloadLinks = [];
  page.on('request', req => {
    const u = req.url();
    if (/\.(zip|shp|geojson|json|kml|kmz|gpkg)(\?|$)/i.test(u)) {
      downloadLinks.push({ url: u, type: 'request' });
    }
  });
  page.on('response', async resp => {
    const ct = resp.headers()['content-type'] || '';
    if (ct.includes('zip') || ct.includes('octet-stream')) {
      downloadLinks.push({ url: resp.url(), type: 'response', ct });
    }
  });

  try {
    const resp = await page.goto(
      'https://www.parks.org.il/files/%D7%94%D7%95%D7%A8%D7%93%D7%AA-%D7%9E%D7%99%D7%93%D7%A2-%D7%92%D7%90%D7%95%D7%92%D7%A8%D7%A4%D7%99/',
      { waitUntil: 'networkidle', timeout: 30000 }
    );
    console.log('Status:', resp?.status(), '| Title:', await page.title());

    const links = await page.evaluate(() =>
      Array.from(document.querySelectorAll('a[href]')).map(a => ({
        text: a.textContent.trim().substring(0, 80),
        href: a.href,
      }))
    );

    console.log('\nAll links on page:');
    for (const { text, href } of links) {
      if (href && !href.includes('#')) console.log(`  [${text}]  ${href}`);
    }
    console.log('\nDownload requests intercepted:', downloadLinks);

    // Also dump page text around שמורות/גנים/GIS/shapefile
    const bodyText = await page.evaluate(() => document.body?.innerText || '');
    const snippets = bodyText
      .split('\n')
      .filter(l => /shapefile|zip|shp|גאו|gis|שמור|שכב|הורד/i.test(l))
      .slice(0, 20);
    console.log('\nRelevant text lines:');
    snippets.forEach(l => console.log(' ', l.trim()));

  } catch (e) {
    console.log('Error:', e.message);
  }
  await ctx.close();
}

// ── 2. Click-probe govmap to trigger entitiesByPoint ─────────────────
async function probeGovmapClick(browser) {
  console.log('\n══════ govmap entitiesByPoint probe ══════');
  const ctx = await browser.newContext({ ...STEALTH_CTX });
  const page = await ctx.newPage();

  const captured = [];
  page.on('response', async resp => {
    const u = resp.url();
    if (u.includes('entitiesByPoint') || u.includes('identify') || u.includes('GetFeatureInfo')) {
      try {
        const text = await resp.text();
        captured.push({ url: u, body: text.substring(0, 2000) });
      } catch {}
    }
  });

  try {
    await page.goto('https://www.govmap.gov.il/?lay=150', { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(3000);

    // GovMap uses EPSG:3857. Ein Gedi reserve centre ≈ lon 35.394 lat 31.457
    // In screen coords we need to click on a point that visually maps to inside Ein Gedi.
    // Try clicking centre of the viewport and see what we get.
    await page.mouse.click(720, 400);
    await page.waitForTimeout(2000);
    await page.mouse.click(640, 450);
    await page.waitForTimeout(2000);
  } catch (e) {
    console.log('Error:', e.message);
  }

  console.log('\nentitiesByPoint responses captured:', captured.length);
  for (const { url, body } of captured) {
    console.log('\n  URL:', url);
    console.log('  Body:', body);
  }
  await ctx.close();
}

// ── 3. Try WMS GetFeatureInfo ─────────────────────────────────────────
async function tryGetFeatureInfo() {
  console.log('\n══════ WMS GetFeatureInfo ══════');
  // A point inside Ein Gedi reserve in EPSG:3857:
  // lon 35.394, lat 31.457 → x=3939200, y=3696800 (approx)
  const url = 'https://www.govmap.gov.il/api/geoserver/ows/public/?' +
    'REQUEST=GetFeatureInfo&SERVICE=WMS&VERSION=1.3.0' +
    '&LAYERS=govmap%3Alayer_atarei_ratag' +
    '&QUERY_LAYERS=govmap%3Alayer_atarei_ratag' +
    '&INFO_FORMAT=application%2Fjson' +
    '&CRS=EPSG%3A3857' +
    '&WIDTH=256&HEIGHT=256' +
    '&BBOX=3913575%2C3669297%2C4070119%2C3825840' +
    '&I=128&J=128' +
    '&FEATURE_COUNT=10';

  return new Promise(resolve => {
    const req = https.get(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/124 Safari/537.36',
        'Referer': 'https://www.govmap.gov.il/',
      }
    }, res => {
      console.log('Status:', res.statusCode, '| Content-Type:', res.headers['content-type']);
      let body = '';
      res.on('data', d => body += d);
      res.on('end', () => { console.log('Body:', body.substring(0, 2000)); resolve(); });
    });
    req.on('error', e => { console.log('Error:', e.message); resolve(); });
  });
}

(async () => {
  const browser = await chromium.launch({
    headless: true,
    args: ['--disable-blink-features=AutomationControlled', '--no-sandbox'],
  });

  try {
    await harvestDownloadPage(browser);
    await probeGovmapClick(browser);
  } finally {
    await browser.close();
  }
  await tryGetFeatureInfo();
})();

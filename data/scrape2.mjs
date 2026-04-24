/**
 * Round 2: target govmap.gov.il (different domain, no CloudFront WAF)
 * and parks.org.il with stealth mode.
 *
 * Goals:
 * 1. On govmap.gov.il/?lay=150 — intercept ALL network requests to find
 *    the service URLs that serve the INPA nature reserve polygons.
 * 2. On parks.org.il/gis/ with stealth — get download links.
 */

import { chromium } from 'playwright';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const ARCGIS_RE = /arcgis\/rest\/services/i;
const FEATURE_RE = /FeatureServer|MapServer|WMSServer|WFSServer/i;
const TILE_RE = /tile\/\d+\/\d+\/\d+/;

function categorise(url) {
  if (ARCGIS_RE.test(url)) return 'arcgis';
  if (url.includes('govmap') && (url.includes('/api/') || url.includes('/rest/'))) return 'govmap-api';
  if (url.includes('.zip') || url.includes('.shp') || url.includes('.geojson')) return 'gis-file';
  return null;
}

async function probeGovmap(browser) {
  console.log('\n==============================');
  console.log('PROBING govmap.gov.il/?lay=150');
  console.log('==============================');

  const ctx = await browser.newContext({
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    locale: 'he-IL',
    viewport: { width: 1280, height: 800 },
  });
  const page = await ctx.newPage();

  const captured = { arcgis: new Set(), govmapApi: new Set(), gisFiles: new Set(), other: new Set() };
  const allUrls = [];

  page.on('request', req => {
    const u = req.url();
    allUrls.push(u);
    const cat = categorise(u);
    if (cat === 'arcgis') captured.arcgis.add(u);
    else if (cat === 'govmap-api') captured.govmapApi.add(u);
    else if (cat === 'gis-file') captured.gisFiles.add(u);
  });

  try {
    await page.goto('https://www.govmap.gov.il/?lay=150', { waitUntil: 'domcontentloaded', timeout: 30000 });
    // Let map tiles and XHR load
    await page.waitForTimeout(8000);
    // Try to interact with the map layer to trigger feature requests
    await page.mouse.click(640, 400);
    await page.waitForTimeout(3000);
  } catch (e) {
    console.log('  [warn]', e.message);
  }

  // Check iframes
  const iframes = await page.evaluate(() =>
    Array.from(document.querySelectorAll('iframe')).map(f => f.src)
  );

  const title = await page.title();
  const bodySnip = await page.evaluate(() => document.body?.innerText?.substring(0, 200)?.replace(/\s+/g, ' ') || '');
  console.log('Title:', title);
  console.log('Body:', bodySnip);
  console.log('Iframes:', iframes);

  console.log('\n-- ArcGIS URLs --');
  if (captured.arcgis.size === 0) console.log('  (none)');
  for (const u of captured.arcgis) console.log(' ', u);

  console.log('\n-- GovMap API calls --');
  if (captured.govmapApi.size === 0) console.log('  (none)');
  for (const u of captured.govmapApi) console.log(' ', u);

  console.log('\n-- GIS file downloads --');
  if (captured.gisFiles.size === 0) console.log('  (none)');
  for (const u of captured.gisFiles) console.log(' ', u);

  // Print ALL unique domain+path prefixes of requests (excluding tiles) to spot services
  const prefixes = new Set();
  for (const u of allUrls) {
    try {
      const url = new URL(u);
      if (!TILE_RE.test(url.pathname)) {
        prefixes.add(url.origin + url.pathname.split('/').slice(0, 5).join('/'));
      }
    } catch {}
  }
  console.log('\n-- All request path prefixes (non-tile) --');
  for (const p of [...prefixes].sort()) console.log(' ', p);

  await ctx.close();
  return captured;
}

async function probeParksStealth(browser) {
  console.log('\n==============================');
  console.log('PROBING parks.org.il/gis/ (stealth headers)');
  console.log('==============================');

  const ctx = await browser.newContext({
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    locale: 'he-IL',
    viewport: { width: 1440, height: 900 },
    extraHTTPHeaders: {
      'Accept-Language': 'he-IL,he;q=0.9,en-US;q=0.8,en;q=0.7',
      'sec-ch-ua': '"Chromium";v="124", "Google Chrome";v="124", "Not-A.Brand";v="99"',
      'sec-ch-ua-mobile': '?0',
      'sec-ch-ua-platform': '"macOS"',
    },
  });
  const page = await ctx.newPage();

  // Spoof webdriver property
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'webdriver', { get: () => false });
    Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3] });
    Object.defineProperty(navigator, 'languages', { get: () => ['he-IL', 'he', 'en-US'] });
  });

  const gisLinks = new Set();

  page.on('request', req => {
    const u = req.url();
    if (u.includes('.zip') || u.includes('.shp') || u.includes('.geojson')) gisLinks.add(u);
  });

  let success = false;
  for (const url of [
    'https://www.parks.org.il/gis/',
    'https://parks.org.il/gis/',
  ]) {
    try {
      const resp = await page.goto(url, { waitUntil: 'networkidle', timeout: 25000 });
      const title = await page.title();
      const body = await page.evaluate(() => document.body?.innerText?.substring(0, 300)?.replace(/\s+/g, ' ') || '');
      console.log(`  ${url} -> status=${resp?.status()} title="${title}"`);
      console.log('  Body:', body);
      if (!title.includes('ERROR') && !body.includes('403')) {
        success = true;
        // Collect download links
        const hrefs = await page.evaluate(() =>
          Array.from(document.querySelectorAll('a[href]')).map(a => ({ text: a.textContent.trim(), href: a.href }))
        );
        console.log('\n  All links:');
        for (const { text, href } of hrefs) {
          if (href) console.log(`    [${text.substring(0, 60)}] ${href}`);
        }
        break;
      }
    } catch (e) {
      console.log('  [warn]', e.message);
    }
  }

  if (!success) console.log('  Still blocked.');

  console.log('\n-- GIS file requests --');
  for (const u of gisLinks) console.log(' ', u);

  await ctx.close();
}

(async () => {
  const browser = await chromium.launch({
    headless: true,
    args: ['--disable-blink-features=AutomationControlled', '--no-sandbox'],
  });

  try {
    await probeGovmap(browser);
    await probeParksStealth(browser);
  } finally {
    await browser.close();
  }
})();

/**
 * Round 4:
 * 1. Dump full HTML of parks.org.il downloads page — find file URLs inside JS/WordPress content.
 * 2. WMS GetFeatureInfo with correct EPSG:3857 coords for Ein Gedi.
 * 3. Try downloading via page interactions (click download buttons).
 */

import { chromium } from 'playwright';
import https from 'https';
import path from 'path';
import { fileURLToPath } from 'url';
import fs from 'fs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const STEALTH_CTX_OPTS = {
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
  locale: 'he-IL',
  viewport: { width: 1440, height: 900 },
  extraHTTPHeaders: {
    'Accept-Language': 'he-IL,he;q=0.9,en-US;q=0.8',
    'sec-ch-ua': '"Chromium";v="124"',
    'sec-ch-ua-mobile': '?0',
    'sec-ch-ua-platform': '"macOS"',
  },
};

async function makeCtx(browser) {
  const ctx = await browser.newContext(STEALTH_CTX_OPTS);
  await ctx.addInitScript(() => {
    Object.defineProperty(navigator, 'webdriver', { get: () => false });
  });
  return ctx;
}

// ── 1. Dump & analyse downloads page HTML ────────────────────────────
async function analyseDownloadPage(browser) {
  console.log('\n══════ Analysing download page HTML ══════');
  const ctx = await makeCtx(browser);
  const page = await ctx.newPage();

  const downloadedFiles = [];
  // Intercept download events
  page.on('download', async dl => {
    const suggested = dl.suggestedFilename();
    const dest = path.join(__dirname, suggested);
    await dl.saveAs(dest);
    downloadedFiles.push(dest);
    console.log('  🎯 Download intercepted:', suggested, '->', dest);
  });

  // Intercept zip/binary responses
  page.on('response', async resp => {
    const ct = resp.headers()['content-type'] || '';
    const u = resp.url();
    if ((ct.includes('zip') || ct.includes('octet-stream') || ct.includes('shapefile')) &&
        !u.includes('.woff') && !u.includes('font')) {
      console.log('  Binary response:', u, ct);
    }
  });

  try {
    await page.goto(
      'https://www.parks.org.il/files/%D7%94%D7%95%D7%A8%D7%93%D7%AA-%D7%9E%D7%99%D7%93%D7%A2-%D7%92%D7%90%D7%95%D7%92%D7%A8%D7%A4%D7%99/',
      { waitUntil: 'networkidle', timeout: 30000 }
    );
  } catch (e) {
    console.log('[warn]', e.message);
  }

  // Save full HTML to inspect
  const html = await page.content();
  fs.writeFileSync(path.join(__dirname, 'downloads_page.html'), html);
  console.log('Saved full HTML to downloads_page.html (' + html.length + ' bytes)');

  // Extract all URLs from the HTML (not just <a> hrefs)
  const urlMatches = html.match(/https?:\/\/[^\s"'<>]+\.(zip|shp|geojson|kml|kmz|gpkg)/gi) || [];
  const jsFileMatches = html.match(/https?:\/\/[^\s"'<>]*(file|download|attachment)[^\s"'<>]*/gi) || [];
  const wpContentMatches = html.match(/https?:\/\/[^\s"'<>]*wp-content\/uploads\/[^\s"'<>]*/gi) || [];

  console.log('\nGIS file URLs in HTML:', urlMatches.length ? urlMatches : '(none)');
  console.log('\nDownload/file URLs in HTML:', jsFileMatches.slice(0, 20));
  console.log('\nwp-content/uploads URLs:', [...new Set(wpContentMatches)].slice(0, 30));

  // Find all button/link elements
  const buttons = await page.evaluate(() =>
    Array.from(document.querySelectorAll('button, [role="button"], a, input[type="button"], input[type="submit"]'))
      .map(el => ({
        tag: el.tagName,
        text: el.textContent?.trim().substring(0, 60),
        href: el.getAttribute('href'),
        onclick: el.getAttribute('onclick'),
        'data-url': el.getAttribute('data-url'),
        'data-file': el.getAttribute('data-file'),
        class: el.className?.substring(0, 60),
      }))
      .filter(el => el.text || el.href)
      .filter(el => el.href?.includes('file') || el.href?.includes('download') ||
                    /שכב|הורד|gis|zip|shp|layer/i.test(el.text) ||
                    el['data-url'] || el['data-file'])
  );
  console.log('\nInteractive download elements:', JSON.stringify(buttons, null, 2));

  // Try scrolling to find lazy-loaded content
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(2000);

  const postScrollHtml = await page.content();
  const newWpUrls = postScrollHtml.match(/https?:\/\/[^\s"'<>]*wp-content\/uploads\/[^\s"'<>]*/gi) || [];
  console.log('\nwp-content/uploads after scroll:', [...new Set(newWpUrls)].slice(0, 30));

  await ctx.close();
}

// ── 2. WMS GetFeatureInfo with correct Ein Gedi coords ───────────────
function tryGetFeatureInfo() {
  console.log('\n══════ WMS GetFeatureInfo (corrected coords) ══════');

  // Ein Gedi in EPSG:3857: approx x=3940500, y=3716800
  // Use a 50km bbox around it
  const minX = 3915500, minY = 3691800, maxX = 3965500, maxY = 3741800;
  // I=128, J=128 → centre of 256x256 tile → Ein Gedi
  const url = 'https://www.govmap.gov.il/api/geoserver/ows/public/?' +
    'REQUEST=GetFeatureInfo&SERVICE=WMS&VERSION=1.3.0' +
    '&LAYERS=govmap%3Alayer_atarei_ratag' +
    '&QUERY_LAYERS=govmap%3Alayer_atarei_ratag' +
    '&INFO_FORMAT=application%2Fjson' +
    '&CRS=EPSG%3A3857' +
    '&WIDTH=256&HEIGHT=256' +
    `&BBOX=${minX}%2C${minY}%2C${maxX}%2C${maxY}` +
    '&I=128&J=128' +
    '&FEATURE_COUNT=10';

  return new Promise(resolve => {
    https.get(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0 Chrome/124',
        'Referer': 'https://www.govmap.gov.il/',
      }
    }, res => {
      console.log('Status:', res.statusCode, '| CT:', res.headers['content-type']);
      let body = '';
      res.on('data', d => body += d);
      res.on('end', () => { console.log(body.substring(0, 3000)); resolve(); });
    }).on('error', e => { console.log('Error:', e.message); resolve(); });
  });
}

// ── 3. Check GovMap layers catalog for download option ───────────────
function checkLayersCatalog() {
  console.log('\n══════ GovMap layers catalog ══════');
  const url = 'https://www.govmap.gov.il/api/layers-catalog/catalog?lang=he';
  return new Promise(resolve => {
    https.get(url, {
      headers: { 'User-Agent': 'Mozilla/5.0 Chrome/124', 'Referer': 'https://www.govmap.gov.il/' }
    }, res => {
      let body = '';
      res.on('data', d => body += d);
      res.on('end', () => {
        // Find entries related to nature reserves
        try {
          const data = JSON.parse(body);
          const flat = JSON.stringify(data);
          // Find "atarei_ratag" or "שמור" mentions
          const lines = flat.match(/.{0,50}(atarei_ratag|שמור|ratag|nature|park|גן|נפ)[^"]{0,50}/gi) || [];
          console.log('Catalog entries mentioning reserves/parks:');
          [...new Set(lines)].slice(0, 20).forEach(l => console.log(' ', l));
          fs.writeFileSync(path.join(__dirname, 'layers_catalog.json'), body);
          console.log('\nFull catalog saved to layers_catalog.json');
        } catch (e) {
          console.log('Parse error:', e.message, body.substring(0, 500));
        }
        resolve();
      });
    }).on('error', e => { console.log('Error:', e.message); resolve(); });
  });
}

(async () => {
  const browser = await chromium.launch({
    headless: true,
    args: ['--disable-blink-features=AutomationControlled', '--no-sandbox'],
  });
  try {
    await analyseDownloadPage(browser);
  } finally {
    await browser.close();
  }
  await tryGetFeatureInfo();
  await checkLayersCatalog();
})();

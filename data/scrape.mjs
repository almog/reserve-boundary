/**
 * Scrapes parks.org.il for the latest INPA nature reserve GIS download.
 *
 * Strategy:
 * 1. Visit parks.org.il/gis/ — intercept all requests and look for download links
 *    in the DOM (href) and in network traffic (zip/shp/geojson responses).
 * 2. Visit parks.org.il/govmap-2/ — intercept XHR/fetch calls to find the
 *    ArcGIS FeatureServer or MapServer URL behind the map.
 * 3. Print all findings so we can decide what to download.
 */

import { chromium } from 'playwright';
import { createWriteStream, existsSync } from 'fs';
import { pipeline } from 'stream/promises';
import https from 'https';
import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const URLS = [
  'https://www.parks.org.il/gis/',
  'https://www.parks.org.il/files/%D7%94%D7%95%D7%A8%D7%93%D7%AA-%D7%9E%D7%99%D7%93%D7%A2-%D7%92%D7%90%D7%95%D7%92%D7%A8%D7%A4%D7%99/',
];

const MAP_URLS = [
  'https://www.parks.org.il/govmap-2/',
  'https://www.parks.org.il/govmap/',
];

const GIS_EXTS = ['.zip', '.shp', '.geojson', '.json', '.kml', '.kmz', '.gpkg', '.gdb'];
const ARCGIS_PATTERNS = [
  /arcgis\/rest\/services/i,
  /FeatureServer/i,
  /MapServer/i,
  /WMSServer/i,
  /WFSServer/i,
];

function isGisFile(url) {
  try {
    const u = new URL(url);
    return GIS_EXTS.some(ext => u.pathname.toLowerCase().endsWith(ext));
  } catch { return false; }
}

function isArcGis(url) {
  return ARCGIS_PATTERNS.some(p => p.test(url));
}

async function probe(browser, url, label) {
  console.log(`\n${'='.repeat(60)}`);
  console.log(`PROBING: ${label}`);
  console.log(`URL: ${url}`);
  console.log('='.repeat(60));

  const page = await browser.newPage();
  const gisLinks = new Set();
  const arcgisUrls = new Set();
  const allRequests = [];

  page.on('request', req => {
    const u = req.url();
    if (isGisFile(u)) gisLinks.add(u);
    if (isArcGis(u)) arcgisUrls.add(u);
    allRequests.push(u);
  });

  page.on('response', async resp => {
    const u = resp.url();
    const ct = resp.headers()['content-type'] || '';
    if (ct.includes('application/zip') || ct.includes('application/octet-stream')) {
      gisLinks.add(u);
    }
  });

  try {
    await page.goto(url, { waitUntil: 'networkidle', timeout: 30000 });
  } catch (e) {
    console.log(`  [warn] navigation: ${e.message}`);
  }

  // Collect all hrefs pointing to GIS files
  const hrefs = await page.evaluate(() =>
    Array.from(document.querySelectorAll('a[href]')).map(a => a.href)
  );
  for (const href of hrefs) {
    if (isGisFile(href)) gisLinks.add(href);
    if (isArcGis(href)) arcgisUrls.add(href);
  }

  // Print all links on the page for manual review
  const allLinks = await page.evaluate(() =>
    Array.from(document.querySelectorAll('a[href]')).map(a => ({
      text: a.textContent.trim().substring(0, 80),
      href: a.href,
    }))
  );

  console.log('\n--- All page links ---');
  for (const { text, href } of allLinks) {
    if (href && href !== url) console.log(`  [${text}] ${href}`);
  }

  console.log('\n--- GIS file links found ---');
  if (gisLinks.size === 0) console.log('  (none)');
  for (const u of gisLinks) console.log(`  ${u}`);

  console.log('\n--- ArcGIS service URLs intercepted ---');
  if (arcgisUrls.size === 0) console.log('  (none)');
  for (const u of arcgisUrls) console.log(`  ${u}`);

  // Also print the page title and first 500 chars of text to verify we got the right page
  const title = await page.title();
  const bodyText = await page.evaluate(() => document.body?.innerText?.substring(0, 500) || '');
  console.log(`\nPage title: ${title}`);
  console.log(`Body preview: ${bodyText.substring(0, 300).replace(/\s+/g, ' ')}`);

  await page.close();
  return { gisLinks: [...gisLinks], arcgisUrls: [...arcgisUrls] };
}

async function probeMap(browser, url, label) {
  console.log(`\n${'='.repeat(60)}`);
  console.log(`PROBING MAP: ${label}`);
  console.log(`URL: ${url}`);
  console.log('='.repeat(60));

  const page = await browser.newPage();
  const arcgisUrls = new Set();
  const interesting = new Set();

  page.on('request', req => {
    const u = req.url();
    if (isArcGis(u)) arcgisUrls.add(u);
    // Catch tile/feature requests to any GIS service
    if (u.includes('gov') && (u.includes('/rest/') || u.includes('service') || u.includes('layer'))) {
      interesting.add(u);
    }
  });

  try {
    await page.goto(url, { waitUntil: 'networkidle', timeout: 45000 });
    // Wait a bit longer for the map to load tiles
    await page.waitForTimeout(5000);
  } catch (e) {
    console.log(`  [warn] navigation: ${e.message}`);
  }

  // Look for iframes (GovMap is often embedded)
  const iframes = await page.evaluate(() =>
    Array.from(document.querySelectorAll('iframe')).map(f => f.src)
  );
  console.log('\n--- iframes ---');
  if (iframes.length === 0) console.log('  (none)');
  for (const src of iframes) console.log(`  ${src}`);

  console.log('\n--- ArcGIS service URLs intercepted ---');
  if (arcgisUrls.size === 0) console.log('  (none)');
  for (const u of arcgisUrls) console.log(`  ${u}`);

  console.log('\n--- Other interesting GIS requests ---');
  const interestingArr = [...interesting].slice(0, 30);
  if (interestingArr.length === 0) console.log('  (none)');
  for (const u of interestingArr) console.log(`  ${u}`);

  const title = await page.title();
  console.log(`\nPage title: ${title}`);

  await page.close();
  return { arcgisUrls: [...arcgisUrls], iframes };
}

async function downloadFile(url, dest) {
  console.log(`\nDownloading: ${url}`);
  console.log(`         to: ${dest}`);
  return new Promise((resolve, reject) => {
    const client = url.startsWith('https') ? https : http;
    const req = client.get(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120 Safari/537.36',
        'Referer': 'https://www.parks.org.il/',
      }
    }, resp => {
      if (resp.statusCode >= 300 && resp.statusCode < 400 && resp.headers.location) {
        return downloadFile(resp.headers.location, dest).then(resolve).catch(reject);
      }
      if (resp.statusCode !== 200) {
        reject(new Error(`HTTP ${resp.statusCode} for ${url}`));
        return;
      }
      const out = createWriteStream(dest);
      resp.pipe(out);
      out.on('finish', () => { console.log('  done.'); resolve(dest); });
      out.on('error', reject);
    });
    req.on('error', reject);
  });
}

(async () => {
  const browser = await chromium.launch({ headless: true });

  const allGisLinks = new Set();
  const allArcGis = new Set();

  // Probe the GIS download pages
  for (const [url, label] of [[URLS[0], 'GIS page'], [URLS[1], 'Files/download page']]) {
    const { gisLinks, arcgisUrls } = await probe(browser, url, label);
    gisLinks.forEach(u => allGisLinks.add(u));
    arcgisUrls.forEach(u => allArcGis.add(u));
  }

  // Probe the map pages for live service URLs
  for (const [url, label] of [[MAP_URLS[0], 'govmap-2'], [MAP_URLS[1], 'govmap']]) {
    const { arcgisUrls, iframes } = await probeMap(browser, url, label);
    arcgisUrls.forEach(u => allArcGis.add(u));
    // If there's a govmap iframe, probe it too
    for (const src of iframes) {
      if (src && src.includes('govmap')) {
        const { arcgisUrls: nested } = await probeMap(browser, src, `iframe: ${src}`);
        nested.forEach(u => allArcGis.add(u));
      }
    }
  }

  await browser.close();

  console.log('\n' + '='.repeat(60));
  console.log('SUMMARY');
  console.log('='.repeat(60));

  console.log('\nAll GIS download links found:');
  if (allGisLinks.size === 0) {
    console.log('  (none detected automatically — check the page link listings above)');
  }
  for (const u of allGisLinks) console.log(`  ${u}`);

  console.log('\nAll ArcGIS service URLs detected:');
  if (allArcGis.size === 0) {
    console.log('  (none)');
  }
  for (const u of allArcGis) console.log(`  ${u}`);

  // If we found a GIS download, grab the most likely zip
  const zipLink = [...allGisLinks].find(u => u.includes('.zip'));
  if (zipLink) {
    const fname = path.basename(new URL(zipLink).pathname) || 'reserves_latest.zip';
    await downloadFile(zipLink, path.join(__dirname, fname));
    console.log(`\nSaved to: ${fname}`);
    console.log('Now run: unzip -o ' + fname + ' -d extracted_latest/ && python3 convert_new.py');
  }
})();

import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const output = path.resolve(here, '../../../web/public/glorious/map');
const userAgent = 'AetherStreamOfflineMap/1.0 (static educational map build)';
// OSM object IDs resolved using bounded Nominatim searches, not hand-picked coordinates.
const places = [
  { name: 'Mysore Palace', id: 'W656323590' },
  { name: 'Infosys campus', id: 'W231782270' },
  { name: 'Kukkarahalli Lake Park', id: 'R9994370' },
  { name: 'Karanji Lake', id: 'R2310424' },
  { name: "St. Philomena's Church", id: 'W28275837' },
  { name: 'Mysuru Zoo', id: 'W28950421' },
  { name: 'Manasagangotri Campus', id: 'W51684020' },
  { name: 'Chamundeshwari Temple, Chamundi Hill', id: 'R5422792' },
  { name: 'JSS Hospital', id: 'W150869510' },
  { name: 'Devaraja Market', id: 'N4764454021' },
];
const pairs = [[1, 6], [6, 0], [0, 4], [9, 8], [2, 3], [8, 7]];

async function getJson(url) {
  const response = await fetch(url, { headers: { 'User-Agent': userAgent } });
  if (!response.ok) throw new Error(`${response.status}: ${url}`);
  return response.json();
}

export async function buildData({ refresh = false } = {}) {
  const cache = path.join(here, 'source-data.json');
  let source;
  if (refresh) {
    const lookupUrl = `https://nominatim.openstreetmap.org/lookup?osm_ids=${places.map(p => p.id).join(',')}&format=jsonv2`;
    const results = await getJson(lookupUrl);
    const landmarks = places.map(place => {
      const hit = results.find(p => `${p.osm_type[0].toUpperCase()}${p.osm_id}` === place.id);
      if (!hit) throw new Error(`OSM object missing: ${place.id}`);
      return { name: place.name, lon: Number(hit.lon), lat: Number(hit.lat), source: `osm:${hit.osm_type}/${hit.osm_id}` };
    });
    source = { fetchedAt: new Date().toISOString(), lookupUrl, lookupResponse: results, landmarks, routes: [] };
    for (const [fromIndex, toIndex] of pairs) {
      const from = landmarks[fromIndex];
      const to = landmarks[toIndex];
      // Request a real driving return leg: reversing a polyline would violate one-way roads.
      const coordinates = [from, to, from].map(p => `${p.lon},${p.lat}`).join(';');
      const url = `https://router.project-osrm.org/route/v1/driving/${coordinates}?overview=full&geometries=geojson&steps=false&continue_straight=false`;
      const response = await getJson(url);
      if (response.code !== 'Ok' || response.routes.length !== 1) throw new Error(`OSRM failed: ${url}`);
      source.routes.push({ from: from.name, to: to.name, url, response });
      await new Promise(resolve => setTimeout(resolve, 1100));
    }
    await writeFile(cache, `${JSON.stringify(source, null, 2)}\n`);
  } else {
    source = JSON.parse(await readFile(cache, 'utf8'));
  }
  const features = source.routes.map((entry, routeIndex) => {
    const geometry = entry.response.routes[0].geometry;
    const first = geometry.coordinates[0];
    const last = geometry.coordinates.at(-1);
    if (geometry.type !== 'LineString' || first[0] !== last[0] || first[1] !== last[1]) {
      throw new Error(`OSRM route ${routeIndex} is not closed; do not fabricate a connecting segment`);
    }
    return { type: 'Feature', properties: { routeIndex, from: entry.from, to: entry.to }, geometry };
  });
  if (features.length !== 6 || source.landmarks.length !== 10) throw new Error('Expected six routes and ten landmarks');
  await mkdir(output, { recursive: true });
  await writeFile(path.join(output, 'landmarks.json'), `${JSON.stringify(source.landmarks, null, 2)}\n`);
  await writeFile(path.join(output, 'routes.geojson'), `${JSON.stringify({ type: 'FeatureCollection', features })}\n`);
  console.log(features.map((f, i) => ({ ...f.properties, points: f.geometry.coordinates.length, meters: source.routes[i].response.routes[0].distance })));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await buildData({ refresh: process.argv.includes('--refresh') });
}

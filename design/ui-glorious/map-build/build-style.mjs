import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const output = path.resolve(here, '../../../web/public/glorious/map');
// Pin the complete asset tree so regenerating cannot silently change font bytes.
const assetCommit = '028c18f713baecad011301ff7a69acc39bcc2ae7';
const fontTree = '99b7e57c7f136e62153faefe18049b2f89bb9434';
const fontRoot = `https://raw.githubusercontent.com/protomaps/basemaps-assets/${assetCommit}/fonts/`;

export async function buildStyle(themeModulePath, { downloadGlyphs = true } = {}) {
  const { layers, namedTheme } = await import(pathToFileURL(path.resolve(themeModulePath)).href);
  const theme = {
    ...structuredClone(namedTheme('light')),
    background: '#f5f1e6', earth: '#f5f1e6',
    park_a: '#dce8ce', park_b: '#c6deb6', wood_a: '#d4e3c6', wood_b: '#b9d5a6',
    scrub_a: '#e0e8cf', scrub_b: '#cdddb8', zoo: '#dae7cf',
    water: '#afd3e1', hospital: '#eee0d9', school: '#ede4d3',
    industrial: '#e8e5dd', military: '#e6e4d8', pedestrian: '#ede8da',
    sand: '#f0e7cb', beach: '#f2e6c5', buildings: '#e7e0d3',
    major: '#f3d686', highway: '#edcc72', link: '#f5dfa1',
    bridges_major: '#f3d686', bridges_highway: '#edcc72', bridges_link: '#f5dfa1',
    minor_a: '#fffefb', minor_b: '#ffffff', minor_service: '#fffefb', other: '#f9f7f0',
    bridges_minor: '#ffffff', bridges_other: '#f9f7f0',
    minor_casing: '#ded8ca', minor_service_casing: '#e6dfd1',
    major_casing_early: '#d8c99f', major_casing_late: '#d8c99f',
    highway_casing_early: '#d2bd88', highway_casing_late: '#d2bd88',
    link_casing: '#dfd2ad', railway: '#b8b1a5', boundaries: '#b7b0a5',
    roads_label_minor: '#5d5a53', roads_label_major: '#53524b',
    roads_label_minor_halo: '#fffdf6', roads_label_major_halo: '#fffdf6',
    city_label: '#42463f', city_label_halo: '#f9f6ec',
    subplace_label: '#62655b', subplace_label_halo: '#f9f6ec',
    state_label: '#66695f', state_label_halo: '#f9f6ec', country_label: '#55594f',
    address_label: '#69665f', address_label_halo: '#fffdf6',
    ocean_label: '#477282', waterway_label: '#4d7481', peak_label: '#65735b',
    pois: { blue: '#555e5b', green: '#50654a', lapis: '#555e5b', pink: '#775e5b', red: '#775e5b', slategray: '#5e6258', tangerine: '#796442', turquoise: '#477282' },
    landcover: { grassland: '#e0eacf', barren: '#f0e7d0', urban_area: '#f2eddf', farmland: '#e3e9d1', glacier: '#f7f7ef', scrub: '#e0e8cf', forest: '#cddfbd' },
  };
  const styleLayers = layers('protomaps', theme, { lang: 'en' });
  function localFont(value, isLayout = false) {
    if (!value || typeof value !== 'object') return;
    for (const [key, child] of Object.entries(value)) {
      if (key === 'text-font') value[key] = isLayout ? ['Noto Sans Regular'] : ['literal', ['Noto Sans Regular']];
      else localFont(child, key === 'layout');
    }
  }
  for (const layer of styleLayers) {
    localFont(layer);
    // Plain text only, including POIs: no sprite requests and no palace/castle artwork.
    if (layer.layout) {
      for (const key of Object.keys(layer.layout)) if (key.startsWith('icon-')) delete layer.layout[key];
      if (layer.id === 'pois') {
        layer.layout['text-offset'] = [0, 0];
        layer.layout['text-variable-anchor'] = ['center', 'top', 'bottom'];
      }
    }
    if (layer.paint) for (const key of Object.keys(layer.paint)) if (key.startsWith('icon-')) delete layer.paint[key];
  }
  const style = {
    version: 8,
    name: 'Mysuru — warm offline light',
    metadata: { 'aetherstream:source': 'protomaps-themes-base@4.5.0 light, BSD-3-Clause', 'aetherstream:bbox': [76.55, 12.23, 76.75, 12.42], 'aetherstream:glyphs': 'Noto Sans Regular, complete upstream BMP ranges, SIL OFL 1.1' },
    glyphs: '/glorious/map/fonts/{fontstack}/{range}.pbf',
    sources: { protomaps: { type: 'vector', url: 'pmtiles:///glorious/map/mysuru.pmtiles', attribution: '<a href="https://protomaps.com">Protomaps</a> © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a> · <a href="https://esa-worldcover.org/en/data-access">ESA WorldCover</a>' } },
    layers: styleLayers,
  };
  await mkdir(path.join(output, 'fonts/Noto Sans Regular'), { recursive: true });
  await writeFile(path.join(output, 'style.json'), `${JSON.stringify(style, null, 2)}\n`);
  if (!downloadGlyphs) return;
  const treeResponse = await fetch(`https://api.github.com/repos/protomaps/basemaps-assets/git/trees/${fontTree}`);
  if (!treeResponse.ok) throw new Error(`Font tree: ${treeResponse.status}`);
  const tree = await treeResponse.json();
  const files = tree.tree.filter(file => file.path.endsWith('.pbf'));
  let cursor = 0;
  await Promise.all(Array.from({ length: 4 }, async () => {
    while (cursor < files.length) {
      const file = files[cursor++];
      const url = `${fontRoot}Noto%20Sans%20Regular/${file.path}`;
      const response = await fetch(url);
      if (!response.ok) throw new Error(`${response.status}: ${url}`);
      const bytes = new Uint8Array(await response.arrayBuffer());
      if (bytes.length !== file.size) throw new Error(`Glyph size changed: ${file.path}`);
      await writeFile(path.join(output, 'fonts/Noto Sans Regular', file.path), bytes);
    }
  }));
  const license = await fetch(`${fontRoot}OFL.txt`);
  if (!license.ok) throw new Error(`Font license: ${license.status}`);
  await writeFile(path.join(output, 'fonts/OFL.txt'), await license.text());
  console.log(`Generated ${styleLayers.length} layers and downloaded ${files.length} local glyph ranges.`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (!process.argv[2]) throw new Error('Usage: node build-style.mjs /temporary/package/dist/esm/index.js');
  await buildStyle(process.argv[2], { downloadGlyphs: !process.argv.includes('--style-only') });
}

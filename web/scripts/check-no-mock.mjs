import { readdir, readFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'

// Scan built text assets for development mock markers: the mock server port (18080),
// its loopback origin, the visible MockBadge text, and mock-only fixture identifiers.
const markers = [
  '18080',
  '127.0.0.1:18080',
  'localhost:18080',
  'MOCK DATA',
  'buildStops',
  'exec-mock-',
]
const distDir = resolve(process.argv[2] ?? 'dist')

async function listFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const nested = await Promise.all(entries.map((entry) => {
    const path = join(directory, entry.name)
    return entry.isDirectory() ? listFiles(path) : [path]
  }))
  return nested.flat()
}

let files
try {
  files = await listFiles(distDir)
} catch (error) {
  console.error(`Mock build check could not read ${distDir}: ${error.message}`)
  process.exit(1)
}

const findings = []
for (const file of files) {
  const contents = await readFile(file, 'utf8')
  for (const marker of markers) {
    if (contents.includes(marker)) findings.push(`${file}: ${JSON.stringify(marker)}`)
  }
}

if (findings.length > 0) {
  console.error(`Mock-only content found in production web build (${distDir}):\n${findings.join('\n')}`)
  process.exit(1)
}

console.log(`No mock-only content found in ${distDir}`)

import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import openapiTS, { astToString } from 'openapi-typescript';

const rawPath = new URL('../../backend/target/openapi.json', import.meta.url);
const specPath = new URL('../../backend/openapi/v1.json', import.meta.url);
const typesPath = new URL('../src/api/generated.ts', import.meta.url);
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  }
  return value;
}
// Require a newly exported backend contract; never silently fall back to a stale snapshot.
const spec = canonical(JSON.parse(await readFile(rawPath, 'utf8')));
const specText = JSON.stringify(spec, null, 2) + '\n';
const typesText = '// Generated from backend OpenAPI. Do not edit.\n' + astToString(await openapiTS(spec));
for (const [path, content] of [[specPath, specText], [typesPath, typesText]]) {
  if (process.argv.includes('--check')) {
    if (await readFile(path, 'utf8') !== content) throw new Error(`Contract drift: ${fileURLToPath(path)}. Run api:generate.`);
  } else await writeFile(path, content);
}

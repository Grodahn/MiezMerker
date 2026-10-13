#!/usr/bin/env node
/**
 * Explicitly local, read-only mkcert public-CA transfer for developer-owned
 * Android devices. Never run as part of Vite, backend, CI, or production.
 */
import { execFileSync } from 'node:child_process';
import { X509Certificate } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { isIP } from 'node:net';
import { join, resolve } from 'node:path';
import { parseArgs } from 'node:util';
import { pathToFileURL } from 'node:url';

export const DOWNLOAD_PATH = '/__dev/cert/rootCA.crt';

export function isAllowedLocalAddress(host) {
  if (typeof host !== 'string' || isIP(host) !== 4) return false;
  const [a, b] = host.split('.').map(Number);
  return a === 10 || (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) || a === 127;
}

export function createPublicCaServer(derBytes) {
  if (!Buffer.isBuffer(derBytes) || derBytes.length === 0) {
    throw new Error('Expected the public CA certificate as non-empty DER bytes');
  }
  return createServer((req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    if (req.method !== 'GET' || req.url !== DOWNLOAD_PATH) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Not found\n');
      return;
    }
    res.writeHead(200, {
      'Content-Type': 'application/x-x509-ca-cert',
      'Content-Disposition': 'attachment; filename="miezmerker-dev-rootCA.crt"',
      'Content-Length': derBytes.length,
    });
    res.end(derBytes);
  });
}

export async function main(args = process.argv.slice(2)) {
  const { values } = parseArgs({
    args,
    options: {
      host: { type: 'string' },
      port: { type: 'string', default: '5174' },
    },
  });
  const host = values.host;
  if (!isAllowedLocalAddress(host)) {
    throw new Error('Supply --host with a literal local IPv4 address (RFC1918 or loopback), e.g. --host 192.168.68.155. Wildcard/public binding is forbidden.');
  }
  if (!/^[0-9]+$/.test(values.port)) {
    throw new Error('Invalid --port: expected integer 1024..65535');
  }
  const port = Number(values.port);
  if (!Number.isSafeInteger(port) || port < 1024 || port > 65535) {
    throw new Error('Invalid --port: expected integer 1024..65535');
  }

  // mkcert tells us where the current local CA lives. Read ONLY its public
  // rootCA.pem; never read, copy, or serve rootCA-key.pem or Vite's key.
  const caRoot = execFileSync('mkcert', ['-CAROOT'], {
    encoding: 'utf8', timeout: 5000, windowsHide: true,
  }).trim();
  if (!caRoot) throw new Error('mkcert -CAROOT returned an empty location');
  const cert = new X509Certificate(readFileSync(join(caRoot, 'rootCA.pem')));
  if (!cert.ca) throw new Error('mkcert rootCA.pem is not a CA certificate');

  const server = createPublicCaServer(cert.raw);
  await new Promise((resolveListen, rejectListen) => {
    server.once('error', rejectListen);
    server.listen(port, host, () => {
      server.off('error', rejectListen);
      resolveListen();
    });
  });
  console.log('LOCAL DEVELOPMENT ONLY – do not forward this port to the Internet.');
  console.log('WARNING: HTTP download can be replaced by someone on the LAN.');
  console.log('Compare this SHA-256 fingerprint using an independent trusted channel BEFORE installing the CA on Android:');
  console.log(cert.fingerprint256);
  console.log(`Download URL: http://${host}:${port}${DOWNLOAD_PATH}`);
  console.log('Stop the server with Ctrl+C. The endpoint is not part of Vite, backend or production builds.');
  return server;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch((error) => {
    console.error(`Development CA server refused to start: ${error.message}`);
    process.exitCode = 1;
  });
}

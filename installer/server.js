const http = require('http');
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');

const ADB = 'C:\\Users\\clientes\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe';
const APK = path.join(__dirname, '..', 'releases', 'SoryxControlRemote-1.0.apk');
const ICON = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'icon-192.png');
const PORT = 4545;

const IP_RE = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;

function isValidIp(ip) {
  const m = IP_RE.exec(ip);
  if (!m) return false;
  return m.slice(1).every((octet) => Number(octet) <= 255);
}

function run(args) {
  return new Promise((resolve) => {
    execFile(ADB, args, { timeout: 20000 }, (err, stdout, stderr) => {
      resolve({ failed: !!err, stdout: stdout || '', stderr: stderr || '' });
    });
  });
}

async function installOnDevice(ip) {
  const target = `${ip}:5555`;

  const connect = await run(['connect', target]);
  const connectOut = (connect.stdout + connect.stderr).trim();
  if (!/connected to/i.test(connectOut)) {
    return { ok: false, step: 'connect', message: connectOut || 'Falha ao conectar via ADB.' };
  }

  const install = await run(['-s', target, 'install', '-r', APK]);
  const installOut = (install.stdout + install.stderr).trim();
  if (!/Success/i.test(installOut)) {
    return { ok: false, step: 'install', message: installOut || 'Falha ao instalar o APK.' };
  }

  return { ok: true, message: 'Instalado com sucesso.' };
}

function serveFile(res, filePath, contentType) {
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end('Not found');
      return;
    }
    res.writeHead(200, { 'Content-Type': contentType });
    res.end(data);
  });
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && (req.url === '/' || req.url === '/index.html')) {
    serveFile(res, path.join(__dirname, 'public', 'index.html'), 'text/html; charset=utf-8');
    return;
  }

  if (req.method === 'GET' && req.url === '/icon.png') {
    serveFile(res, ICON, 'image/png');
    return;
  }

  if (req.method === 'POST' && req.url === '/install') {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', async () => {
      try {
        const { ip } = JSON.parse(body || '{}');
        if (!ip || !isValidIp(ip)) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, message: 'IP inválido.' }));
          return;
        }
        const result = await installOnDevice(ip);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(result));
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, message: String(e) }));
      }
    });
    return;
  }

  res.writeHead(404);
  res.end('Not found');
});

server.listen(PORT, () => {
  console.log(`Soryx installer rodando em http://localhost:${PORT}`);
});

import http from 'node:http';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import dotenv from 'dotenv';
import { createServer as createViteServer } from 'vite';

import { DEFAULT_ENDPOINT, DEFAULT_MODEL, DEFAULT_PROMPT, normalizeEndpoint, renderPrompt } from './src/lib/settings.js';

const root = path.dirname(fileURLToPath(import.meta.url));
dotenv.config({ path: path.join(root, '.env.local') });
dotenv.config({ path: path.join(root, '.env') });

const port = Number(process.env.PORT || 4173);
const hostFlagIndex = process.argv.indexOf('--host');
const host = hostFlagIndex >= 0 ? (process.argv[hostFlagIndex + 1] || '0.0.0.0') : (process.env.HOST || '127.0.0.1');

function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
  });
  res.end(payload);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
      if (body.length > 24 * 1024 * 1024) {
        reject(new Error('请求体超过 24MB'));
        req.destroy();
      }
    });
    req.on('end', () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch {
        reject(new Error('请求 JSON 格式错误'));
      }
    });
    req.on('error', reject);
  });
}

function externalConfig(body = {}) {
  return {
    endpoint: body.endpoint || process.env.QUIZ_API_URL || DEFAULT_ENDPOINT,
    model: body.model || process.env.QUIZ_MODEL || DEFAULT_MODEL,
    apiKey: body.apiKey || process.env.QUIZ_API_KEY || '',
    prompt: body.prompt || process.env.QUIZ_PROMPT || DEFAULT_PROMPT,
    timeoutMs: Math.min(Math.max(Number(body.timeoutMs) || 30000, 5000), 120000),
    retryCount: Math.min(Math.max(Math.round(Number(body.retryCount) || 0), 0), 5),
  };
}

async function fetchWithTimeout(url, options, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

function upstreamErrorMessage(error) {
  if (error?.name === 'AbortError') return '模型请求超时，请检查网络或增大超时时间';
  return error instanceof Error ? error.message.slice(0, 300) : '模型请求失败';
}

function extractContent(payload) {
  const content = payload?.choices?.[0]?.message?.content;
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map((part) => part?.text || '').join('');
  return '';
}

async function callModel({ config, image, questionNo, connectionCheck = false }) {
  if (!config.apiKey) throw new Error('本地代理尚未配置 API Key');
  const messages = connectionCheck
    ? [
        { role: 'system', content: '只回复 READY。' },
        { role: 'user', content: '连接测试。' },
      ]
    : [
        { role: 'system', content: renderPrompt(config.prompt, { questionNo }) },
        {
          role: 'user',
          content: [
            { type: 'text', text: `这是第 ${questionNo || '?'} 题，请按系统提示词返回 JSON。` },
            { type: 'image_url', image_url: { url: image } },
          ],
        },
      ];

  const requestBody = {
    model: config.model,
    messages,
    temperature: 0,
    max_tokens: connectionCheck ? 8 : 1200,
  };

  let response = await fetchWithTimeout(normalizeEndpoint(config.endpoint), {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${config.apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      ...requestBody,
      ...(connectionCheck ? {} : { response_format: { type: 'json_object' } }),
    }),
  }, config.timeoutMs);

  if (!response.ok && !connectionCheck) {
    const firstError = await response.text();
    if (response.status === 400 || response.status === 422) {
      response = await fetchWithTimeout(normalizeEndpoint(config.endpoint), {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${config.apiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(requestBody),
      }, config.timeoutMs);
    } else {
      throw new Error(`上游模型返回 ${response.status}: ${firstError.slice(0, 240)}`);
    }
  }

  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(`上游模型返回 ${response.status}: ${String(payload?.error?.message || '请求失败').slice(0, 240)}`);
  }
  return extractContent(payload) || (connectionCheck ? 'READY' : '');
}

async function callModelWithRetry(args) {
  let lastError;
  for (let attempt = 0; attempt <= args.config.retryCount; attempt += 1) {
    try {
      return await callModel(args);
    } catch (error) {
      lastError = error;
      const message = String(error?.message || '');
      if (attempt >= args.config.retryCount || /\b401\b|\b403\b/.test(message)) throw error;
      await new Promise((resolve) => setTimeout(resolve, 450 * (attempt + 1)));
    }
  }
  throw lastError;
}

async function handleApi(req, res) {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': 'Content-Type',
    });
    res.end();
    return true;
  }

  if (req.method === 'GET' && url.pathname === '/api/config') {
    sendJson(res, 200, {
      endpoint: process.env.QUIZ_API_URL || DEFAULT_ENDPOINT,
      model: process.env.QUIZ_MODEL || DEFAULT_MODEL,
      keyConfigured: Boolean(process.env.QUIZ_API_KEY),
    });
    return true;
  }

  if (req.method !== 'POST' || !['/api/analyze', '/api/test-connection'].includes(url.pathname)) return false;

  try {
    const body = await readBody(req);
    const config = externalConfig(body);
    if (url.pathname === '/api/test-connection') {
      const content = await callModelWithRetry({ config, connectionCheck: true });
      sendJson(res, 200, { ok: true, content });
    } else if (typeof body.image !== 'string' || !body.image.startsWith('data:image/')) {
      sendJson(res, 400, { error: '请提供 data:image/* 格式的截图' });
    } else {
      const content = await callModelWithRetry({ config, image: body.image, questionNo: body.questionNo });
      sendJson(res, 200, { ok: true, content });
    }
  } catch (error) {
    sendJson(res, 502, { error: upstreamErrorMessage(error) });
  }
  return true;
}

const vite = await createViteServer({
  root,
  configFile: path.join(root, 'vite.config.js'),
  server: { middlewareMode: true },
  appType: 'spa',
});

const server = http.createServer(async (req, res) => {
  if (req.url?.startsWith('/api/')) {
    const handled = await handleApi(req, res);
    if (handled) return;
  }
  vite.middlewares(req, res, () => {
    res.statusCode = 404;
    res.end('Not found');
  });
});

server.listen(port, host, () => {
  const displayHost = host === '0.0.0.0' ? '127.0.0.1' : host;
  console.log(`Quiz Control Room running at http://${displayHost}:${port}`);
  console.log(`Model preset: ${process.env.QUIZ_MODEL || DEFAULT_MODEL}`);
  console.log(`API key configured: ${Boolean(process.env.QUIZ_API_KEY)}`);
});

process.on('SIGINT', async () => {
  await vite.close();
  server.close(() => process.exit(0));
});

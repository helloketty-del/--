import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_PROMPT,
  DEFAULT_QUESTION_BANK_PROMPT,
  normalizeEndpoint,
  readBrowserSettings,
  renderPrompt,
  sanitizeSettings,
  saveBrowserSettings,
} from '../src/lib/settings.js';

test('normalizes a v1 base URL to the chat completions endpoint', () => {
  assert.equal(
    normalizeEndpoint('https://api.openai.com/v1/'),
    'https://api.openai.com/v1/chat/completions',
  );
});

test('preserves an explicit chat completions URL', () => {
  assert.equal(
    normalizeEndpoint('https://example.test/v1/chat/completions'),
    'https://example.test/v1/chat/completions',
  );
});

test('fills missing settings from safe defaults and keeps the prompt editable', () => {
  const settings = sanitizeSettings({ model: 'gpt-4o-mini' });
  assert.equal(settings.model, 'gpt-4o-mini');
  assert.equal(settings.endpoint, 'https://api.openai.com/v1');
  assert.equal(settings.prompt, DEFAULT_PROMPT);
  assert.equal(settings.mode, 'manual');
  assert.equal(settings.targetApp, 'auto');
  assert.match(settings.prompt, /\{QUESTION_NO\}/);
  assert.match(settings.prompt, /confirm_box/);
  assert.match(settings.prompt, /确认/);
});

test('normalizes automatic and fixed target preferences', () => {
  assert.equal(sanitizeSettings({ targetApp: 'auto' }).targetApp, 'auto');
  assert.equal(sanitizeSettings({ targetApp: 'simulator' }).targetApp, 'simulator');
  assert.equal(sanitizeSettings({ targetApp: 'wechat' }).targetApp, 'wechat');
  assert.equal(sanitizeSettings({ targetApp: 'unknown' }).targetApp, 'auto');
});

test('question bank source supports automatic mode with an independent prompt', () => {
  const settings = sanitizeSettings({
    answerSource: 'question_bank',
    mode: 'auto',
    questionBankPrompt: '自定义题库提示词',
  });
  assert.equal(settings.answerSource, 'question_bank');
  assert.equal(settings.mode, 'auto');
  assert.equal(settings.questionBankPrompt, '自定义题库提示词');
  assert.match(DEFAULT_QUESTION_BANK_PROMPT, /question/);
});

test('browser persistence removes a cleared persistent key', () => {
  const originalWindow = globalThis.window;
  const writes = new Map();
  const local = new Map([['quiz-control-api-key', 'OLD_KEY']]);
  const session = new Map([['quiz-control-api-key', 'LEGACY_KEY']]);
  globalThis.window = {
    localStorage: {
      setItem: (key, value) => {
        writes.set(key, value);
        local.set(key, value);
      },
      removeItem: (key) => local.delete(key),
    },
    sessionStorage: {
      setItem: (key, value) => session.set(key, value),
      removeItem: (key) => session.delete(key),
    },
  };

  saveBrowserSettings({ endpoint: 'https://example.test', apiKey: '' });
  assert.equal(local.has('quiz-control-api-key'), false);
  assert.equal(session.has('quiz-control-api-key'), false);
  assert.equal(writes.has('quiz-control-settings'), true);
  globalThis.window = originalWindow;
});

test('saved API key is restored after an app reload', () => {
  const originalWindow = globalThis.window;
  const local = new Map();
  const session = new Map();
  globalThis.window = {
    localStorage: {
      getItem: (key) => local.get(key) ?? null,
      setItem: (key, value) => local.set(key, value),
      removeItem: (key) => local.delete(key),
    },
    sessionStorage: {
      getItem: (key) => session.get(key) ?? null,
      setItem: (key, value) => session.set(key, value),
      removeItem: (key) => session.delete(key),
    },
  };

  try {
    saveBrowserSettings({
      endpoint: 'https://example.test/v1',
      model: 'fixture-model',
      apiKey: 'SAVED_KEY',
    });
    session.clear();

    const restored = readBrowserSettings();
    assert.equal(local.get('quiz-control-api-key'), 'SAVED_KEY');
    assert.equal(restored.apiKey, 'SAVED_KEY');
    assert.equal(restored.model, 'fixture-model');
  } finally {
    globalThis.window = originalWindow;
  }
});

test('saved prompt is restored after an app reload', () => {
  const originalWindow = globalThis.window;
  const local = new Map();
  const session = new Map();
  globalThis.window = {
    localStorage: {
      getItem: (key) => local.get(key) ?? null,
      setItem: (key, value) => local.set(key, value),
      removeItem: (key) => local.delete(key),
    },
    sessionStorage: {
      getItem: (key) => session.get(key) ?? null,
      setItem: (key, value) => session.set(key, value),
      removeItem: (key) => session.delete(key),
    },
  };

  try {
    const prompt = '示例第 {QUESTION_NO} 题：{QUESTION_IMAGE}';
    saveBrowserSettings({ prompt, model: 'gpt-4o-mini', mode: 'auto' });
    assert.equal(readBrowserSettings().prompt, prompt);
  } finally {
    globalThis.window = originalWindow;
  }
});

test('repairs a legacy empty persisted prompt before the next native session starts', () => {
  const originalWindow = globalThis.window;
  const local = new Map([
    ['quiz-control-settings', JSON.stringify({
      endpoint: 'https://example.test/v1',
      model: 'fixture-model',
      prompt: '',
      mode: 'auto',
      targetApp: 'simulator',
    })],
    ['quiz-control-api-key', 'SAVED_KEY'],
  ]);
  const session = new Map();
  globalThis.window = {
    localStorage: {
      getItem: (key) => local.get(key) ?? null,
      setItem: (key, value) => local.set(key, value),
      removeItem: (key) => local.delete(key),
    },
    sessionStorage: {
      getItem: (key) => session.get(key) ?? null,
      setItem: (key, value) => session.set(key, value),
      removeItem: (key) => session.delete(key),
    },
  };

  try {
    const restored = readBrowserSettings();

    assert.equal(restored.prompt, DEFAULT_PROMPT);
    assert.equal(restored.apiKey, 'SAVED_KEY');
    assert.equal(JSON.parse(local.get('quiz-control-settings')).prompt, DEFAULT_PROMPT);
  } finally {
    globalThis.window = originalWindow;
  }
});

test('migrates the previous built-in prompt to support indefinite-choice questions', () => {
  const originalWindow = globalThis.window;
  const legacyPrompt = DEFAULT_PROMPT
    .replace('single、multiple、indefinite 或 true_false', 'single、multiple 或 true_false')
    .replace('多选题和不定项选择题', '多选题');
  const local = new Map([[
    'quiz-control-settings',
    JSON.stringify({ prompt: legacyPrompt, model: 'fixture-model' }),
  ]]);
  globalThis.window = {
    localStorage: {
      getItem: (key) => local.get(key) ?? null,
      setItem: (key, value) => local.set(key, value),
      removeItem: (key) => local.delete(key),
    },
    sessionStorage: { getItem: () => null, removeItem: () => {} },
  };
  try {
    const restored = readBrowserSettings();
    assert.equal(restored.prompt, DEFAULT_PROMPT);
    assert.equal(restored.questionBankPrompt, DEFAULT_QUESTION_BANK_PROMPT);
    assert.equal(JSON.parse(local.get('quiz-control-settings')).prompt, DEFAULT_PROMPT);
  } finally {
    globalThis.window = originalWindow;
  }
});

test('renders prompt placeholders without embedding the screenshot payload in text', () => {
  assert.equal(
    renderPrompt('第 {QUESTION_NO} 题：{QUESTION_IMAGE}', { questionNo: 12 }),
    '第 12 题：题目截图已作为 image_url 附件提供',
  );
});

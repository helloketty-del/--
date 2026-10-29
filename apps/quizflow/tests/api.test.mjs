import test from 'node:test';
import assert from 'node:assert/strict';
import { analyzeScreenshot } from '../src/lib/api.js';

function response(content) {
  return {
    ok: true,
    async json() {
      return { ok: true, content };
    },
  };
}

test('题库未命中时立即用视觉提示词兜底，下一次调用仍可使用题库提示词', async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (_url, init) => {
    calls.push(JSON.parse(init.body));
    if (calls.length === 1) return response('{"question":"当前题库没有这道题"}');
    return response(JSON.stringify({
      question_no: 3,
      question_type: 'indefinite',
      question: '当前题库没有这道题',
      options: [
        { index: 0, text: 'A', box: [10, 20, 80, 10] },
        { index: 1, text: 'B', box: [10, 32, 80, 10] },
      ],
      answer_indices: [0, 1],
      confirm_box: [20, 80, 60, 8],
      confidence: 0.9,
    }));
  };
  try {
    const result = await analyzeScreenshot({
      image: 'data:image/png;base64,SCREEN',
      questionNo: 3,
      settings: {
        endpoint: 'https://example.test/v1',
        model: 'fixture-model',
        apiKey: 'TOKEN',
        prompt: 'VISION_PROMPT',
        questionBankPrompt: 'BANK_PROMPT',
        answerSource: 'question_bank',
        timeoutMs: 5000,
        retryCount: 0,
      },
    });
    assert.equal(calls.length, 2);
    assert.equal(calls[0].prompt, 'BANK_PROMPT');
    assert.equal(calls[1].prompt, 'VISION_PROMPT');
    assert.equal(result.match_status, 'vision_fallback');
    assert.deepEqual(result.answer_indices, [0, 1]);
    assert.equal(result.question_type, 'indefinite');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

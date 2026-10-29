import { parseModelResult } from './quizEngine.js';
import { analyzeWithNative, isNativeAvailable, testWithNative } from './nativeBridge.js';
import { renderPrompt } from './settings.js';
import questionBank from '../data/question-bank.json' with { type: 'json' };
import {
  buildQuestionBankIndex,
  matchQuestionStem,
  parseQuestionStemResult,
  questionBankMatchToResult,
} from './questionBank.js';

const questionBankIndex = buildQuestionBankIndex(questionBank.questions);

async function requestModelContent({ image, settings, questionNo, prompt }) {
  const renderedPrompt = renderPrompt(prompt, { questionNo });
  if (isNativeAvailable()) {
    return analyzeWithNative({
      image,
      questionNo,
      endpoint: settings.endpoint,
      model: settings.model,
      apiKey: settings.apiKey || '',
      prompt: renderedPrompt,
      timeoutMs: settings.timeoutMs,
      retryCount: settings.retryCount,
    });
  }
  const response = await fetch('/api/analyze', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      image,
      questionNo,
      endpoint: settings.endpoint,
      model: settings.model,
      apiKey: settings.apiKey || '',
      prompt: renderedPrompt,
      timeoutMs: settings.timeoutMs,
      retryCount: settings.retryCount,
    }),
  });
  const payload = await parseResponse(response);
  return payload.content;
}

async function parseResponse(response) {
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || `请求失败（${response.status}）`);
  return payload;
}

export async function analyzeScreenshot({ image, settings, questionNo }) {
  if (!image) throw new Error('请先提供题目截图');
  const isQuestionBankMode = settings.answerSource === 'question_bank';
  const content = await requestModelContent({
    image,
    settings,
    questionNo,
    prompt: isQuestionBankMode ? settings.questionBankPrompt : settings.prompt,
  });
  if (isQuestionBankMode) {
    const stem = parseQuestionStemResult(content);
    const bankResult = questionBankMatchToResult(
      matchQuestionStem(questionBankIndex, stem.question),
      stem.question,
      questionNo,
    );
    if (['not_found', 'ambiguous', 'conflict'].includes(bankResult.match_status)) {
      const visualResult = parseModelResult(await requestModelContent({
        image,
        settings,
        questionNo,
        prompt: settings.prompt,
      }));
      return {
        ...visualResult,
        match_status: 'vision_fallback',
        match_label: '视觉模型兜底',
        match_source: null,
      };
    }
    return parseModelResult(bankResult);
  }
  return parseModelResult(content);
}

export async function testConnection(settings) {
  if (isNativeAvailable()) {
    const content = await testWithNative({
      endpoint: settings.endpoint,
      model: settings.model,
      apiKey: settings.apiKey || '',
      timeoutMs: settings.timeoutMs,
      retryCount: settings.retryCount,
    });
    return { ok: true, content };
  }
  const response = await fetch('/api/test-connection', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      endpoint: settings.endpoint,
      model: settings.model,
      apiKey: settings.apiKey || '',
      timeoutMs: settings.timeoutMs,
      retryCount: settings.retryCount,
    }),
  });
  return parseResponse(response);
}

export function readFileAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error('读取截图失败'));
    reader.readAsDataURL(file);
  });
}

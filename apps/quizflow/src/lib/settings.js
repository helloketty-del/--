import { sanitizeSwipeProfiles, swipeDirection, sanitizeCustomClicks } from './swipeSettings.js';
export const DEFAULT_ENDPOINT = 'https://api.openai.com/v1';
export const DEFAULT_MODEL = 'gpt-4o-mini';
const SETTINGS_STORAGE_KEY = 'quiz-control-settings';
const API_KEY_STORAGE_KEY = 'quiz-control-api-key';
export const DEFAULT_PROMPT = `你是一个严谨的识题与答题引擎。请读取当前截图，识别完整题干、题型、全部选项和“确认”按钮，并选择最符合题意的答案。\n\n当前题号：{QUESTION_NO}\n题目截图：{QUESTION_IMAGE}\n\n规则：\n1. question_type 只能是 single、multiple、indefinite 或 true_false。\n2. answer_indices 使用从 0 开始的选项编号；多选题和不定项选择题返回全部正确答案，且编号不得重复。\n3. 仔细识别“不正确”“错误的是”“不包括”“不属于”“最主要”“首要”等限定词；涉及地区、时间、条件或活动时以题干条件为准。\n4. 每个 option 必须返回 box: [x,y,width,height]，表示该选项实际可点击区域。\n5. 所有题型选择答案后都需要点击“确认”才能进入下一页；confirm_box 返回当前题“确认”按钮的可点击区域。\n6. confirm_box 只表示本题确认按钮，不得把最终“提交”按钮作为 confirm_box；看不清确认按钮时返回 null。\n7. 所有坐标均为截图宽高的 0 到 100 百分比，并确保 x+width、y+height 不超过 100。\n8. confidence 使用 0 到 1 的小数。\n9. question_no 必须等于 {QUESTION_NO}。\n10. 只返回一个合法 JSON 对象，不要 Markdown 代码围栏、分析过程或额外说明。\n\n返回结构：\n{"question_no":{QUESTION_NO},"question_type":"single","question":"完整题干","options":[{"index":0,"text":"完整选项文字","box":[8,43,84,8]}],"answer_indices":[0],"confirm_box":[20,88,60,8],"next_box":null,"confidence":0.95,"next_action":"tap_answer_then_confirm"}`;
const LEGACY_DEFAULT_PROMPT = DEFAULT_PROMPT
  .replace('single、multiple、indefinite 或 true_false', 'single、multiple 或 true_false')
  .replace('多选题和不定项选择题', '多选题');
export const DEFAULT_QUESTION_BANK_PROMPT = `你是题库题干提取器。请只读取当前截图中的题干，不要解题，不要输出选项答案，不要输出坐标。\n\n题目截图：{QUESTION_IMAGE}\n\n规则：\n1. 完整抄录题干，保留“不正确”“错误的是”“不包括”“不属于”等限定词。\n2. 去除题号、状态栏、按钮文字和选项内容。\n3. 填空题保留题干中的括号、下划线或空缺位置。\n4. 只返回一个合法 JSON 对象，不要 Markdown 或额外说明。\n\n返回结构：{"question":"完整题干"}`;

export function renderPrompt(prompt, { questionNo } = {}) {
  const renderedQuestionNo = questionNo === null || questionNo === undefined ? '?' : questionNo;
  return String(prompt || DEFAULT_PROMPT)
    .split('{QUESTION_NO}').join(String(renderedQuestionNo))
    .split('{QUESTION_IMAGE}').join('题目截图已作为 image_url 附件提供');
}

export function normalizeEndpoint(value = DEFAULT_ENDPOINT) {
  const raw = String(value || DEFAULT_ENDPOINT).trim().replace(/\/+$/, '');
  if (/\/chat\/completions$/i.test(raw)) return raw;
  return `${raw}/chat/completions`;
}

export function sanitizeSettings(input = {}) {
  const answerSource = input.answerSource === 'question_bank' ? 'question_bank' : 'vision';
  const requestedMode = ['auto', 'manual', 'capture'].includes(input.mode) ? input.mode : 'manual';
  const mode = requestedMode;
  const timeout = Number(input.timeoutMs);
  const retries = Number(input.retryCount);
  const startQuestionNo = Number(input.startQuestionNo);

  return {
    endpoint: String(input.endpoint || DEFAULT_ENDPOINT).trim() || DEFAULT_ENDPOINT,
    model: String(input.model || DEFAULT_MODEL).trim() || DEFAULT_MODEL,
    prompt: String(input.prompt || DEFAULT_PROMPT).trim() || DEFAULT_PROMPT,
    questionBankPrompt: String(input.questionBankPrompt || DEFAULT_QUESTION_BANK_PROMPT).trim() || DEFAULT_QUESTION_BANK_PROMPT,
    answerSource,
    mode,
    navigation: swipeDirection(input.navigation),
    swipeProfiles: sanitizeSwipeProfiles(input.swipeProfiles),
    customClicks: sanitizeCustomClicks(input.customClicks),
    autoSubmit: input.autoSubmit === true,
    bankFallback: input.bankFallback !== false,
    minConfidence: Number.isFinite(Number(input.minConfidence)) ? Math.min(1, Math.max(0.5, Number(input.minConfidence))) : 0.85,
    maxScrolls: Number.isFinite(Number(input.maxScrolls)) ? Math.min(40, Math.max(1, Math.round(Number(input.maxScrolls)))) : 12,
    settleMs: Number.isFinite(Number(input.settleMs)) ? Math.min(10000, Math.max(500, Math.round(Number(input.settleMs)))) : 1000,
    automationInstructions: String(input.automationInstructions || '').slice(0, 4000),
    targetApp: ['auto', 'wechat', 'simulator'].includes(input.targetApp) ? input.targetApp : 'auto',
    timeoutMs: Number.isFinite(timeout) ? Math.min(Math.max(timeout, 5000), 120000) : 30000,
    retryCount: Number.isFinite(retries) ? Math.min(Math.max(Math.round(retries), 0), 5) : 2,
    startQuestionNo: Number.isFinite(startQuestionNo)
      ? Math.min(Math.max(Math.round(startQuestionNo), 1), 9999)
      : 1,
  };
}

export function maskSecret(value = '') {
  const secret = String(value);
  if (!secret) return '';
  if (secret.length <= 8) return `${secret.slice(0, 2)}••••${secret.slice(-2)}`;
  return `${secret.slice(0, 4)}••••••••${secret.slice(-4)}`;
}

export function readBrowserSettings() {
  if (typeof window === 'undefined') return sanitizeSettings();
  try {
    const stored = window.localStorage.getItem(SETTINGS_STORAGE_KEY);
    const persistentKey = window.localStorage.getItem(API_KEY_STORAGE_KEY) || '';
    const legacySessionKey = window.sessionStorage?.getItem(API_KEY_STORAGE_KEY) || '';
    const savedSettings = stored ? JSON.parse(stored) : {};
    const settings = sanitizeSettings(savedSettings);
    const savedPrompt = String(savedSettings.prompt || '').trim();
    const savedQuestionBankPrompt = String(savedSettings.questionBankPrompt || '').trim();
    const needsPromptMigration = !savedPrompt || savedPrompt === LEGACY_DEFAULT_PROMPT.trim();
    const migratedSettings = needsPromptMigration ? { ...settings, prompt: DEFAULT_PROMPT } : settings;
    if (stored && (needsPromptMigration || !savedQuestionBankPrompt)) {
      window.localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(migratedSettings));
    }
    return {
      ...migratedSettings,
      apiKey: persistentKey || legacySessionKey,
    };
  } catch {
    return sanitizeSettings();
  }
}

export function saveBrowserSettings(settings) {
  if (typeof window === 'undefined') return;
  const safe = sanitizeSettings(settings);
  const apiKey = String(settings.apiKey || '').trim();
  window.localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(safe));
  if (apiKey) window.localStorage.setItem(API_KEY_STORAGE_KEY, apiKey);
  else window.localStorage.removeItem(API_KEY_STORAGE_KEY);
  window.sessionStorage?.removeItem(API_KEY_STORAGE_KEY);
}

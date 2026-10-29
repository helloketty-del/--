function getBridge() {
  return typeof window !== 'undefined' ? window.AndroidBridge : undefined;
}

let requestCounter = 0;
let pendingScreenshotRequest = null;

function nativeRequest(method, payload) {
  const bridge = getBridge();
  if (!bridge?.[method] || typeof window === 'undefined') return Promise.resolve(null);
  const requestId = `quiz-${Date.now()}-${requestCounter += 1}`;
  if (!window.__quizNativeModelRequests) window.__quizNativeModelRequests = new Map();
  if (!window.__quizNativeModelCallbackInstalled) {
    window.onNativeModelResponse = (id, content, error) => {
      const pending = window.__quizNativeModelRequests.get(id);
      if (!pending) return;
      window.__quizNativeModelRequests.delete(id);
      if (error) pending.reject(new Error(String(error)));
      else pending.resolve(String(content || ''));
    };
    window.__quizNativeModelCallbackInstalled = true;
  }
  return new Promise((resolve, reject) => {
    window.__quizNativeModelRequests.set(requestId, { resolve, reject });
    bridge[method](JSON.stringify(payload), requestId);
    const timeoutMs = Math.min(Math.max(Number(payload?.timeoutMs) || 30000, 5000), 120000);
    const retries = Math.min(Math.max(Number(payload?.retryCount) || 0, 0), 5);
    window.setTimeout(() => {
      const pending = window.__quizNativeModelRequests.get(requestId);
      if (!pending) return;
      window.__quizNativeModelRequests.delete(requestId);
      reject(new Error('原生模型请求超时'));
    }, timeoutMs * (retries + 1) + 8000);
  });
}

export function isNativeAvailable() {
  return Boolean(getBridge());
}

export function getNativeCapabilities() {
  const bridge = getBridge();
  if (!bridge?.capabilities) return { capture: false, gesture: false };
  try {
    return JSON.parse(String(bridge.capabilities()));
  } catch {
    return { capture: false, gesture: false };
  }
}

export function startFloatingSession(settings) {
  const bridge = getBridge();
  if (!bridge?.startFloatingSession) return false;
  try {
    return bridge.startFloatingSession(JSON.stringify(settings)) !== false;
  } catch {
    return false;
  }
}

export function stopFloatingSession() {
  const bridge = getBridge();
  if (!bridge?.stopFloatingSession) return false;
  try {
    bridge.stopFloatingSession();
    return true;
  } catch {
    return false;
  }
}

export function getFloatingCapabilities() {
  const fallback = { overlay: false, capture: false, gesture: false, sessionRunning: false };
  const bridge = getBridge();
  if (!bridge?.floatingCapabilities) return fallback;
  try {
    return { ...fallback, ...JSON.parse(String(bridge.floatingCapabilities())) };
  } catch {
    return fallback;
  }
}

export function tapNormalized(x, y, targetApp = 'auto') {
  const bridge = getBridge();
  if (!bridge?.tapNormalized) return false;
  const normalizedX = Number(x) > 1 ? Number(x) / 100 : Number(x);
  const normalizedY = Number(y) > 1 ? Number(y) / 100 : Number(y);
  return bridge.tapNormalized(normalizedX, normalizedY, targetApp) !== false;
}

export function tapOption(option, targetApp = 'auto') {
  const box = option?.box;
  if (!Array.isArray(box) || box.length < 4) return false;
  const [x, y, width, height] = box.map(Number);
  return tapNormalized(x + width / 2, y + height / 2, targetApp);
}

export function tapConfirmButton(box = null, targetApp = 'auto') {
  if (Array.isArray(box) && box.length >= 4) {
    const [x, y, width, height] = box.map(Number);
    if ([x, y, width, height].every(Number.isFinite) && width > 0 && height > 0) {
      return tapNormalized(x + width / 2, y + height / 2, targetApp);
    }
  }
  const bridge = getBridge();
  if (!bridge?.tapText) return false;
  return bridge.tapText('确认', targetApp) !== false;
}

export function cancelNativeGestures() {
  const bridge = getBridge();
  if (bridge?.cancelGestures) bridge.cancelGestures();
}

export function stopNativeCapture() {
  const bridge = getBridge();
  if (bridge?.stopCapture) bridge.stopCapture();
}

export function openTargetApp(targetApp = 'auto') {
  const bridge = getBridge();
  return bridge?.openTargetApp ? bridge.openTargetApp(targetApp) !== false : false;
}

export function openNativeAccessibilitySettings() {
  const bridge = getBridge();
  if (bridge?.openAccessibilitySettings) bridge.openAccessibilitySettings();
}

export function analyzeWithNative(payload) {
  return nativeRequest('analyze', payload);
}

export function testWithNative(payload) {
  return nativeRequest('testConnection', payload);
}

export function requestNativeScreenshot() {
  const bridge = getBridge();
  if (!bridge?.requestScreenshot) return Promise.resolve('');
  if (pendingScreenshotRequest) return pendingScreenshotRequest;
  const promise = new Promise((resolve, reject) => {
    const previous = window.onNativeScreenshot;
    const timeout = window.setTimeout(() => {
      window.onNativeScreenshot = previous;
      reject(new Error('原生截图超时'));
    }, 15000);
    window.onNativeScreenshot = (dataUrl) => {
      window.clearTimeout(timeout);
      window.onNativeScreenshot = previous;
      resolve(String(dataUrl || ''));
    };
    bridge.requestScreenshot();
  });
  pendingScreenshotRequest = promise;
  const clear = () => {
    if (pendingScreenshotRequest === promise) pendingScreenshotRequest = null;
  };
  promise.then(clear, clear);
  return promise;
}

function bankRequest(method, payload) {
  const bridge = getBridge();
  if (!bridge?.[method] || typeof window === 'undefined') return Promise.resolve(null);
  const requestId = `bank-${Date.now()}-${requestCounter += 1}`;
  if (!window.__quizBankRequests) window.__quizBankRequests = new Map();
  if (!window.__quizBankCallbackInstalled) {
    window.onNativeQuestionBankResult = (id, body) => {
      const pending = window.__quizBankRequests.get(id);
      if (!pending) return;
      window.__quizBankRequests.delete(id);
      try {
        pending.resolve(JSON.parse(String(body || '{}')));
      } catch (error) {
        pending.reject(error);
      }
    };
    window.__quizBankCallbackInstalled = true;
  }
  return new Promise((resolve, reject) => {
    window.__quizBankRequests.set(requestId, { resolve, reject });
    window.setTimeout(() => {
      const pending = window.__quizBankRequests.get(requestId);
      if (!pending) return;
      window.__quizBankRequests.delete(requestId);
      reject(new Error('题库操作超时'));
    }, 12000);
    bridge[method](JSON.stringify({ ...payload, requestId }));
  });
}

export function listQuestionBank({ query = '', offset = 0, limit = 50 } = {}) {
  return bankRequest('questionBankList', { query, offset, limit });
}

export function saveQuestionBankRecord(record) {
  return bankRequest('questionBankSave', { record });
}

export function deleteQuestionBankRecord(id) {
  return bankRequest('questionBankDelete', { id });
}

export function importQuestionBank(content) {
  return bankRequest('questionBankImport', { content });
}

export function exportQuestionBank() {
  const bridge = getBridge();
  if (!bridge?.questionBankExport) return false;
  try {
    bridge.questionBankExport();
    return true;
  } catch {
    return false;
  }
}

export function isQuestionBankAvailable() {
  const bridge = getBridge();
  return Boolean(bridge?.questionBankList);
}

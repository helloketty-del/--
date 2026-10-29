import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  Bot,
  Check,
  CheckCircle2,
  ChevronRight,
  CircleHelp,
  Clock3,
  Code2,
  Database,
  Download,
  ExternalLink,
  FileImage,
  Gauge,
  KeyRound,
  Layers3,
  LockKeyhole,
  Menu,
  Pause,
  Play,
  Plus,
  RotateCcw,
  Save,
  ScanLine,
  Search,
  Settings2,
  ShieldCheck,
  Sparkles,
  Square,
  Terminal,
  Trash2,
  Upload,
  Wifi,
  X,
  Zap,
} from 'lucide-react';

import {
  advanceRun,
  createRunState,
  frameFingerprint,
  getActionPlan,
  pauseRun,
  resumeRun,
  startRun,
  stopRun,
} from './lib/quizEngine.js';
import {
  DEFAULT_ENDPOINT,
  DEFAULT_MODEL,
  DEFAULT_PROMPT,
  DEFAULT_QUESTION_BANK_PROMPT,
  maskSecret,
  readBrowserSettings,
  sanitizeSettings,
  saveBrowserSettings,
} from './lib/settings.js';
import { analyzeScreenshot, readFileAsDataUrl, testConnection } from './lib/api.js';
import {
  cancelNativeGestures,
  deleteQuestionBankRecord,
  exportQuestionBank,
  getFloatingCapabilities,
  importQuestionBank,
  isNativeAvailable,
  isQuestionBankAvailable,
  listQuestionBank,
  openTargetApp,
  requestNativeScreenshot,
  saveQuestionBankRecord,
  startFloatingSession,
  stopFloatingSession,
  stopNativeCapture,
  tapConfirmButton,
  tapOption,
} from './lib/nativeBridge.js';
import { getMockQuestion, getQuestionTypeLabel } from './data/mockQuestions.js';

const navItems = [
  { id: 'run', label: '运行台', hint: 'LIVE CONTROL', icon: Gauge },
  { id: 'bank', label: '题库', hint: 'QUESTION BANK', icon: Database },
  { id: 'settings', label: '模型设置', hint: 'MODEL CONFIG', icon: Settings2 },
  { id: 'history', label: '运行记录', hint: 'SESSION LOG', icon: Layers3 },
];

const initialLogs = [
  { time: '刚刚', tone: 'success', text: '练题流已就绪，等待启用。' },
  { time: '刚刚', tone: 'info', text: '运行桥接已就绪：截图 / 模型 / 点击 / 页面变化。' },
];

const wait = (milliseconds) => new Promise((resolve) => window.setTimeout(resolve, milliseconds));

function optionFor(question, index) {
  return question?.options?.find((option) => Number(option.index) === Number(index));
}

function formatTime(date = new Date()) {
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function modeLabel(mode) {
  if (mode === 'capture') return '采集模式';
  return mode === 'auto' ? '自动刷题' : '手动辅助';
}

function targetAppLabel(targetApp) {
  if (targetApp === 'simulator') return '题库模拟器';
  if (targetApp === 'wechat') return '微信题库';
  return '当前题目页';
}

function statusLabel(status) {
  return {
    idle: '待启动',
    active: '运行中',
    paused: '已暂停',
    stopped: '已停止',
  }[status] || status;
}

function statusTone(status) {
  if (status === 'active') return 'live';
  return 'neutral';
}

function App() {
  const [view, setView] = useState('run');
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [settings, setSettings] = useState(() => ({
    ...readBrowserSettings(),
    endpoint: readBrowserSettings().endpoint || DEFAULT_ENDPOINT,
    model: readBrowserSettings().model || DEFAULT_MODEL,
    prompt: readBrowserSettings().prompt || DEFAULT_PROMPT,
    questionBankPrompt: readBrowserSettings().questionBankPrompt || DEFAULT_QUESTION_BANK_PROMPT,
  }));
  const [serverConfig, setServerConfig] = useState({ keyConfigured: false });
  const [run, setRun] = useState(() => createRunState({ mode: readBrowserSettings().mode }));
  const [question, setQuestion] = useState(() => getMockQuestion(1));
  const [logs, setLogs] = useState(initialLogs);
  const [phase, setPhase] = useState('ready');
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');
  const [connection, setConnection] = useState('unknown');
  const [isBusy, setIsBusy] = useState(false);
  const [uploadedImage, setUploadedImage] = useState('');
  const [uploadedName, setUploadedName] = useState('');
  const [testBusy, setTestBusy] = useState(false);
  const [floatingSessionRunning, setFloatingSessionRunning] = useState(false);
  const [floatingSessionPreparing, setFloatingSessionPreparing] = useState(false);
  const [floatingCapabilities, setFloatingCapabilities] = useState(() => (
    isNativeAvailable() ? getFloatingCapabilities() : {
      overlay: false, capture: false, gesture: false, sessionRunning: false,
    }
  ));
  const timerRef = useRef(null);
  const toastRef = useRef(null);
  const executionIdRef = useRef(0);
  const runRef = useRef(run);
  const questionRef = useRef(question);
  const settingsRef = useRef(settings);
  const uploadedImageRef = useRef(uploadedImage);
  const preparedQuestionRef = useRef(null);
  const retryModeRef = useRef('capture');

  useEffect(() => { runRef.current = run; }, [run]);
  useEffect(() => { questionRef.current = question; }, [question]);
  useEffect(() => { settingsRef.current = settings; }, [settings]);
  useEffect(() => { uploadedImageRef.current = uploadedImage; }, [uploadedImage]);

  const isActive = run.status === 'active';
  const canEditSettings = !floatingSessionRunning && !floatingSessionPreparing
    && !['active', 'waiting_page_change'].includes(run.status) && !isBusy;

  const addLog = useCallback((text, tone = 'info') => {
    setLogs((current) => [{ time: formatTime(), tone, text }, ...current].slice(0, 8));
  }, []);

  const notify = useCallback((message) => {
    setToast(message);
    window.clearTimeout(toastRef.current);
    toastRef.current = window.setTimeout(() => setToast(''), 2800);
  }, []);

  useEffect(() => {
    if (!isNativeAvailable()) return undefined;
    const initialCapabilities = getFloatingCapabilities();
    setFloatingCapabilities(initialCapabilities);
    setFloatingSessionRunning(initialCapabilities.sessionRunning);
    const previousErrorHandler = window.onNativeFloatingSessionError;
    const previousStateHandler = window.onNativeFloatingSessionState;
    const errorHandler = (message) => {
      const detail = String(message || '悬浮识题启动失败');
      setFloatingSessionPreparing(false);
      setFloatingSessionRunning(false);
      setFloatingCapabilities(getFloatingCapabilities());
      setError(detail);
      setPhase('error');
      addLog(detail, 'warning');
    };
    const stateHandler = (running) => {
      const next = { ...getFloatingCapabilities(), sessionRunning: Boolean(running) };
      setFloatingSessionPreparing(false);
      setFloatingSessionRunning(Boolean(running));
      setFloatingCapabilities(next);
    };
    window.onNativeFloatingSessionError = errorHandler;
    window.onNativeFloatingSessionState = stateHandler;
    return () => {
      if (window.onNativeFloatingSessionError === errorHandler) {
        window.onNativeFloatingSessionError = previousErrorHandler;
      }
      if (window.onNativeFloatingSessionState === stateHandler) {
        window.onNativeFloatingSessionState = previousStateHandler;
      }
    };
  }, [addLog]);

  useEffect(() => {
    if (isNativeAvailable()) {
      setConnection(settingsRef.current.apiKey ? 'ready' : 'missing');
      return;
    }
    fetch('/api/config')
      .then((response) => (response.ok ? response.json() : null))
      .then((config) => {
        if (!config) return;
        setServerConfig(config);
        setConnection(config.keyConfigured ? 'ready' : 'missing');
        setSettings((current) => ({
          ...current,
          endpoint: current.endpoint === DEFAULT_ENDPOINT ? config.endpoint || DEFAULT_ENDPOINT : current.endpoint,
          model: current.model === DEFAULT_MODEL ? config.model || DEFAULT_MODEL : current.model,
        }));
      })
      .catch(() => setConnection('offline'));
  }, []);

  useEffect(() => () => {
    window.clearTimeout(timerRef.current);
    window.clearTimeout(toastRef.current);
  }, []);

  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'auto' });
  }, [view]);

  useEffect(() => {
    if (run.status !== 'active' || isBusy || error) return undefined;
    const cursor = `${run.mode}:${run.questionNo}`;
    const shouldPrepareManual = run.mode === 'manual' && preparedQuestionRef.current !== cursor;
    if (run.mode !== 'auto' && !shouldPrepareManual) return undefined;
    timerRef.current = window.setTimeout(() => {
      executeQuestion(run.mode === 'auto' ? 'auto' : 'manual');
    }, isNativeAvailable() ? 1800 : run.mode === 'auto' ? 700 : 180);
    return () => window.clearTimeout(timerRef.current);
  }, [run.mode, run.status, run.questionNo, isBusy, error]);

  const setCurrentQuestion = (nextQuestion) => {
    questionRef.current = nextQuestion;
    setQuestion(nextQuestion);
  };

  const isCurrentExecution = (executionId) => executionIdRef.current === executionId;

  const validateQuestionNumber = (result, questionNo) => {
    if (result?.question_no !== null && result?.question_no !== questionNo) {
      throw new Error(`模型返回第 ${result.question_no} 题，当前应为第 ${questionNo} 题`);
    }
    return result;
  };

  const captureAndAnalyze = async (questionNo, executionId) => {
    if (isNativeAvailable()) {
      const image = await requestNativeScreenshot();
      if (!image) throw new Error('原生截图未返回内容');
      uploadedImageRef.current = image;
      setUploadedImage(image);
      setUploadedName('native-screen.jpg');
      if (!isCurrentExecution(executionId)) return null;
      const result = validateQuestionNumber(await analyzeScreenshot({
        image,
        settings: settingsRef.current,
        questionNo,
      }), questionNo);
      if (!isCurrentExecution(executionId)) return null;
      setCurrentQuestion(result);
      preparedQuestionRef.current = `${runRef.current.mode}:${questionNo}`;
      addLog(`第 ${questionNo} 题：真实模型识别完成，置信度 ${Math.round(result.confidence * 100)}%。`, 'success');
      return { image, result };
    }

    // The browser preview has no screen-capture bridge; keep its deterministic fixture loop.
    const result = getMockQuestion(questionNo);
    setCurrentQuestion(result);
    preparedQuestionRef.current = `${runRef.current.mode}:${questionNo}`;
    return { image: '', result };
  };

  const waitForNativePageChange = async (previousFrame, executionId) => {
    if (!isNativeAvailable()) {
      await wait(520);
      return '';
    }
    const previousFingerprint = frameFingerprint(previousFrame);
    for (let attempt = 0; attempt < 12; attempt += 1) {
      await wait(attempt === 0 ? 650 : 450);
      if (!isCurrentExecution(executionId)) return null;
      const nextFrame = await requestNativeScreenshot();
      if (!nextFrame) continue;
      uploadedImageRef.current = nextFrame;
      setUploadedImage(nextFrame);
      setUploadedName('native-screen.jpg');
      if (!previousFingerprint || frameFingerprint(nextFrame) !== previousFingerprint) return nextFrame;
    }
    throw new Error('点击后未检测到下一题页面变化，运行已暂停');
  };

  const executeNativeAction = async (result, executionId) => {
    const plan = getActionPlan(result);
    if (plan.missingOptions.length > 0) {
      throw new Error(`模型返回了不存在的选项：${plan.missingOptions.join(', ')}`);
    }
    if (plan.missingBoxes.length > 0) {
      throw new Error('模型没有为答案返回有效坐标，已暂停自动点击');
    }
    for (const option of plan.answerOptions) {
      if (!tapOption(option, settingsRef.current.targetApp)) throw new Error('无障碍点击服务未启用或坐标无效');
      await wait(150);
      if (!isCurrentExecution(executionId)) return false;
    }
    if (!tapConfirmButton(plan.confirmBox, settingsRef.current.targetApp)) throw new Error('确认按钮点击失败');
    await wait(180);
    return true;
  };

  const executeQuestion = async (executionMode) => {
    const snapshotRun = runRef.current;
    if (isBusy || snapshotRun.status !== 'active') return;
    const executionId = ++executionIdRef.current;
    setIsBusy(true);
    setError('');
    setPhase('capturing');
    retryModeRef.current = 'capture';
    addLog(`第 ${snapshotRun.questionNo} 题：截取当前页面`, 'info');
    try {
      setPhase('recognizing');
      const captured = await captureAndAnalyze(snapshotRun.questionNo, executionId);
      if (!captured || !isCurrentExecution(executionId)) return;
      const result = captured.result;
      if (executionMode === 'manual') {
        setPhase('ready');
        return;
      }

      setPhase('executing');
      retryModeRef.current = 'action';
      const action = result.question_type === 'multiple'
        ? '依次点击选项，再点击确认'
        : '点击答案，再点击确认';
      addLog(`第 ${snapshotRun.questionNo} 题：${action}`, 'success');
      if (isNativeAvailable()) {
        const actionAccepted = await executeNativeAction(result, executionId);
        if (!actionAccepted || !isCurrentExecution(executionId)) return;
      } else {
        await wait(result.question_type === 'multiple' ? 680 : 480);
      }

      if (!isCurrentExecution(executionId)) return;
      setPhase('waiting_page_change');
      retryModeRef.current = 'page';
      const nextFrame = await waitForNativePageChange(captured.image, executionId);
      if (!isCurrentExecution(executionId)) return;
      if (nextFrame) {
        addLog('已检测到下一题页面。', 'info');
      }

      const next = advanceRun(snapshotRun, result.question_type);
      runRef.current = next;
      setRun(next);
      if (!isNativeAvailable()) setCurrentQuestion(getMockQuestion(next.questionNo));
      setPhase('ready');
    } catch (executionError) {
      if (!isCurrentExecution(executionId)) return;
      setError(executionError.message || '本题执行失败');
      setPhase('error');
      addLog(`第 ${snapshotRun.questionNo} 题：${executionError.message || '执行失败'}`, 'warning');
    } finally {
      if (isCurrentExecution(executionId)) setIsBusy(false);
    }
  };

  const handleStart = async () => {
    executionIdRef.current += 1;
    setError('');
    setPhase('ready');
    preparedQuestionRef.current = null;
    const freshRun = runRef.current.status === 'stopped';
    const nextMode = freshRun || runRef.current.status === 'idle'
      ? settingsRef.current.mode
      : runRef.current.mode;

    if (isNativeAvailable()) {
      const apiKey = String(settingsRef.current.apiKey || '').trim();
      if (!apiKey) {
        setError('请先在模型设置中填写 API Key');
        setPhase('error');
        setView('settings');
        return;
      }
      const accepted = startFloatingSession({...sanitizeSettings(settingsRef.current), apiKey});
      if (!accepted) {
        setError('练题流原生桥启动失败，请重试');
        setPhase('error');
        addLog('练题流原生桥启动失败，请重试', 'warning');
        return;
      }
      setFloatingSessionPreparing(true);
      setFloatingCapabilities(getFloatingCapabilities());
      addLog('正在准备悬浮窗、自动点击与屏幕捕获权限。', 'info');
      return;
    }

    if (freshRun) {
      const nextRun = startRun(createRunState({ mode: nextMode }));
      const nextQuestion = getMockQuestion(1);
      runRef.current = nextRun;
      questionRef.current = nextQuestion;
      setQuestion(nextQuestion);
      setRun(nextRun);
    } else {
      const baseRun = runRef.current.status === 'idle'
        ? { ...runRef.current, mode: nextMode }
        : runRef.current;
      const nextRun = startRun(baseRun);
      runRef.current = nextRun;
      setRun(nextRun);
    }
    if (isNativeAvailable()) {
      window.setTimeout(() => {
        const targetLabel = targetAppLabel(settingsRef.current.targetApp);
        if (settingsRef.current.targetApp === 'auto') addLog('已返回上一应用，请保持题目页可见。', 'info');
        else if (openTargetApp(settingsRef.current.targetApp)) addLog(`已打开${targetLabel}，请保持题目页可见。`, 'info');
        else addLog(`未找到${targetLabel}，请先手动打开题目页。`, 'warning');
      }, 250);
    }
    addLog(`${modeLabel(nextMode)}运行已启动。`, 'success');
  };

  const handlePause = () => {
    executionIdRef.current += 1;
    cancelNativeGestures();
    window.clearTimeout(timerRef.current);
    setIsBusy(false);
    setRun((current) => pauseRun(current));
    setPhase('paused');
    addLog('运行已暂停，当前题目保持不变。', 'warning');
  };

  const handleResume = async () => {
    if (isBusy) return;
    const pausedRun = runRef.current.status === 'paused' ? runRef.current : run;
    if (pausedRun.status !== 'paused') return;
    const executionId = ++executionIdRef.current;
    retryModeRef.current = 'capture';
    setError('');

    if (isNativeAvailable()) {
      setIsBusy(true);
      setPhase('capturing');
      try {
        const targetLabel = targetAppLabel(settingsRef.current.targetApp);
        if (settingsRef.current.targetApp !== 'auto' && !openTargetApp(settingsRef.current.targetApp)) throw new Error(`未找到${targetLabel}，请先手动打开题目页`);
        addLog(settingsRef.current.targetApp === 'auto' ? '正在检查当前题目页，等待页面稳定。' : `正在切回${targetLabel}，等待题目页稳定。`, 'info');
        await wait(1000);
        if (!isCurrentExecution(executionId)) return;
      } catch (resumeError) {
        if (!isCurrentExecution(executionId)) return;
        setError(resumeError.message || '恢复运行失败');
        setPhase('paused');
        addLog(resumeError.message || '恢复运行失败', 'warning');
        return;
      } finally {
        if (isCurrentExecution(executionId)) setIsBusy(false);
      }
    }

    if (!isCurrentExecution(executionId)) return;
    const resumed = resumeRun(pausedRun);
    runRef.current = resumed;
    setRun(resumed);
    setPhase('ready');
    addLog('运行已恢复。', 'success');
  };

  const handleStop = () => {
    if (isNativeAvailable()) {
      if (!stopFloatingSession()) {
        setError('停止悬浮识题失败，请重试');
        setPhase('error');
        return;
      }
      executionIdRef.current += 1;
      window.clearTimeout(timerRef.current);
      setFloatingSessionPreparing(false);
      setFloatingSessionRunning(false);
      setFloatingCapabilities({ ...getFloatingCapabilities(), sessionRunning: false, capture: false });
      setIsBusy(false);
      setPhase('stopped');
      addLog('悬浮识题会话已停止。', 'warning');
      return;
    }
    executionIdRef.current += 1;
    cancelNativeGestures();
    stopNativeCapture();
    window.clearTimeout(timerRef.current);
    setIsBusy(false);
    setRun((current) => stopRun(current));
    setPhase('stopped');
    addLog('运行已停止，进度已保留。', 'warning');
  };

  const handleReset = () => {
    executionIdRef.current += 1;
    cancelNativeGestures();
    if (isNativeAvailable()) {
      stopFloatingSession();
      setFloatingSessionPreparing(false);
      setFloatingSessionRunning(false);
      setFloatingCapabilities({ ...getFloatingCapabilities(), sessionRunning: false, capture: false });
    } else {
      stopNativeCapture();
    }
    window.clearTimeout(timerRef.current);
    setIsBusy(false);
    setError('');
    preparedQuestionRef.current = null;
    const nextRun = createRunState({ mode: settings.mode });
    const nextQuestion = getMockQuestion(1);
    runRef.current = nextRun;
    questionRef.current = nextQuestion;
    setQuestion(nextQuestion);
    setRun(nextRun);
    setPhase('ready');
    addLog('已创建新一轮运行。', 'info');
  };

  const handleManualAdvance = () => {
    const snapshotRun = runRef.current;
    const snapshotQuestion = questionRef.current;
    if (snapshotRun.status !== 'active' || isBusy) return;
    const executionId = ++executionIdRef.current;
    setIsBusy(true);
    setPhase('waiting_page_change');
    retryModeRef.current = 'page';
    addLog(`第 ${snapshotRun.questionNo} 题：已记录手动操作，等待下一页。`, 'info');
    (async () => {
      try {
        if (isNativeAvailable()) {
          const targetLabel = targetAppLabel(settingsRef.current.targetApp);
          if (settingsRef.current.targetApp !== 'auto' && !openTargetApp(settingsRef.current.targetApp)) throw new Error(`未找到${targetLabel}，请先手动打开题目页`);
          addLog(settingsRef.current.targetApp === 'auto' ? '正在检查当前题目页，等待下一题页面稳定。' : `正在切回${targetLabel}，等待下一题页面稳定。`, 'info');
          await wait(1000);
          if (!isCurrentExecution(executionId)) return;
        }
        const nextFrame = await waitForNativePageChange(uploadedImageRef.current, executionId);
        if (!isCurrentExecution(executionId)) return;
        const next = advanceRun(snapshotRun, snapshotQuestion.question_type);
        runRef.current = next;
        setRun(next);
        if (nextFrame && isNativeAvailable()) {
          setPhase('recognizing');
          const result = validateQuestionNumber(await analyzeScreenshot({
            image: nextFrame,
            settings: settingsRef.current,
            questionNo: next.questionNo,
          }), next.questionNo);
          if (!isCurrentExecution(executionId)) return;
          setCurrentQuestion(result);
          preparedQuestionRef.current = `${next.mode}:${next.questionNo}`;
          addLog(`第 ${next.questionNo} 题：真实模型识别完成，置信度 ${Math.round(result.confidence * 100)}%。`, 'success');
          setPhase('ready');
        } else {
          setCurrentQuestion(getMockQuestion(next.questionNo));
          preparedQuestionRef.current = `${next.mode}:${next.questionNo}`;
          setPhase('ready');
        }
      } catch (advanceError) {
        if (!isCurrentExecution(executionId)) return;
        setError(advanceError.message || '等待下一题失败');
        setPhase('error');
        addLog(`第 ${snapshotRun.questionNo} 题：${advanceError.message || '等待下一题失败'}`, 'warning');
      } finally {
        if (isCurrentExecution(executionId)) setIsBusy(false);
      }
    })();
  };

  const handleRetry = () => {
    if (retryModeRef.current === 'action' || retryModeRef.current === 'page') {
      executionIdRef.current += 1;
      cancelNativeGestures();
      const paused = pauseRun(runRef.current);
      runRef.current = paused;
      setRun(paused);
      setError('');
      setPhase('paused');
      addLog('自动动作未确认，已暂停；请确认微信当前页面后再继续。', 'warning');
      return;
    }
    setError('');
    executeQuestion(runRef.current.mode);
  };

  const handleModeChange = (mode) => {
    if (!canEditSettings) {
      notify('请先暂停或停止当前运行');
      return;
    }
    setSettings((current) => ({ ...current, mode }));
    settingsRef.current = { ...settingsRef.current, mode };
    const nextRun = { ...runRef.current, mode };
    runRef.current = nextRun;
    setRun(nextRun);
    addLog(`运行模式切换为${modeLabel(mode)}。`, 'info');
  };

  const handleSettingsChange = (field, value) => {
    const nextSettings = { ...settingsRef.current, [field]: value };
    settingsRef.current = nextSettings;
    setSettings(nextSettings);
    if (field === 'apiKey' && isNativeAvailable()) setConnection(value ? 'ready' : 'missing');
    if ((field === 'mode' || field === 'answerSource') && ['idle', 'stopped'].includes(runRef.current.status)) {
      const nextRun = { ...runRef.current, mode: nextSettings.mode };
      runRef.current = nextRun;
      setRun(nextRun);
    }
  };

  const handleSaveSettings = () => {
    if (!canEditSettings) {
      notify('运行中请先暂停，再保存设置');
      return;
    }
    const currentSettings = settingsRef.current;
    const safe = sanitizeSettings(currentSettings);
    const savedSettings = { ...currentSettings, ...safe };
    saveBrowserSettings({ ...safe, apiKey: currentSettings.apiKey || '' });
    settingsRef.current = savedSettings;
    setSettings(savedSettings);
    const nextRun = { ...runRef.current, mode: safe.mode };
    runRef.current = nextRun;
    setRun(nextRun);
    addLog(`配置已保存：${safe.model} · ${modeLabel(safe.mode)}`, 'success');
    notify('配置已保存到本地');
  };

  const handleResetPrompt = (promptType = settingsRef.current.answerSource) => {
    handleSettingsChange(
      promptType === 'question_bank' ? 'questionBankPrompt' : 'prompt',
      promptType === 'question_bank' ? DEFAULT_QUESTION_BANK_PROMPT : DEFAULT_PROMPT,
    );
  };

  const handleTestConnection = async () => {
    setTestBusy(true);
    setConnection('testing');
    try {
      await testConnection(settings);
      setConnection('ready');
      addLog('模型连接测试通过。', 'success');
      notify('连接测试通过');
    } catch (connectionError) {
      setConnection('error');
      addLog(`连接测试失败：${connectionError.message}`, 'warning');
      notify('连接测试失败');
    } finally {
      setTestBusy(false);
    }
  };

  const handleFileChange = async (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    try {
      const dataUrl = await readFileAsDataUrl(file);
      uploadedImageRef.current = dataUrl;
      setUploadedImage(dataUrl);
      setUploadedName(file.name);
      setError('');
      notify('截图已载入，可调用真实模型识别');
    } catch (fileError) {
      setError(fileError.message);
    }
  };

  const handleRealAnalyze = async () => {
    if (!uploadedImage) {
      notify('先上传一张题目截图');
      return;
    }
    setIsBusy(true);
    setPhase('recognizing');
    setError('');
    try {
      const result = validateQuestionNumber(
        await analyzeScreenshot({ image: uploadedImage, settings, questionNo: run.questionNo }),
        run.questionNo,
      );
      setCurrentQuestion(result);
      preparedQuestionRef.current = `${run.mode}:${run.questionNo}`;
      addLog(`第 ${run.questionNo} 题：真实模型识别完成，置信度 ${Math.round(result.confidence * 100)}%。`, 'success');
      setPhase('ready');
    } catch (analysisError) {
      setError(analysisError.message);
      setPhase('error');
      addLog(`模型识别失败：${analysisError.message}`, 'warning');
    } finally {
      setIsBusy(false);
    }
  };

  const handleNativeCapture = async () => {
    setIsBusy(true);
    setError('');
    try {
      const dataUrl = await requestNativeScreenshot();
      if (!dataUrl) throw new Error('原生截图未返回内容');
      uploadedImageRef.current = dataUrl;
      setUploadedImage(dataUrl);
      setUploadedName('native-screen.jpg');
      addLog('原生屏幕截图已回传到运行台。', 'success');
      notify('原生截图已载入');
    } catch (captureError) {
      setError(captureError.message);
      addLog(`原生截图失败：${captureError.message}`, 'warning');
    } finally {
      setIsBusy(false);
    }
  };

  const connectionText = {
    unknown: '检查代理',
    ready: '代理已连接',
    missing: '等待 Key',
    testing: '测试中',
    error: '连接异常',
    offline: '代理离线',
  }[connection];

  return (
    <div className="app-shell">
      <aside className={`sidebar ${mobileNavOpen ? 'sidebar-open' : ''}`}>
        <div className="brand-lockup">
          <div className="brand-mark"><ScanLine size={19} strokeWidth={2.5} /></div>
          <div>
            <div className="brand-name">练题流</div>
            <div className="brand-caption">悬浮识题助手</div>
          </div>
          <button className="icon-button sidebar-close" onClick={() => setMobileNavOpen(false)} aria-label="关闭菜单"><X size={17} /></button>
        </div>

        <div className="sidebar-section-label">WORKSPACE</div>
        <nav className="nav-list" aria-label="主导航">
          {navItems.map(({ id, label, hint, icon: Icon }) => (
            <button
              className={`nav-item ${view === id ? 'nav-item-active' : ''}`}
              key={id}
              onClick={() => { setView(id); setMobileNavOpen(false); }}
            >
              <Icon size={18} strokeWidth={1.8} />
              <span><strong>{label}</strong><small>{hint}</small></span>
              {view === id && <ChevronRight size={15} className="nav-arrow" />}
            </button>
          ))}
        </nav>

        <div className="sidebar-spacer" />
        <div className="bridge-card">
          <div className="bridge-card-head"><span className="status-dot status-dot-live" /> ANDROID BRIDGE</div>
          <p>安卓端支持屏幕捕获、模型识别、自动点击与滑动。</p>
          <div className="bridge-progress"><span style={{ width: '100%' }} /></div>
          <div className="bridge-meta"><span>ANDROID BUILD</span><strong>0.8.0</strong></div>
        </div>
        <div className="sidebar-footer"><ShieldCheck size={14} /> 本地配置优先 · Key 脱敏</div>
      </aside>

      <main className="main-canvas">
        <header className="topbar">
          <button className="icon-button mobile-menu" onClick={() => setMobileNavOpen(true)} aria-label="打开菜单"><Menu size={20} /></button>
          <div className="breadcrumb"><span>WORKSPACE</span><ChevronRight size={13} /><strong>{view === 'run' ? '运行台' : view === 'bank' ? '题库' : view === 'settings' ? '模型设置' : '运行记录'}</strong></div>
          <div className="topbar-actions">
            <div className={`connection-pill connection-${connection}`}><span className="status-dot" /> {connectionText}</div>
            <div className="model-chip"><Bot size={15} /> {settings.model}</div>
            <button className="avatar-button" aria-label="个人配置">QC</button>
          </div>
        </header>

        {view === 'run' && (
          <RunView
            run={run}
            question={question}
            phase={phase}
            error={error}
            logs={logs}
            isBusy={isBusy}
            uploadedImage={uploadedImage}
            uploadedName={uploadedName}
            onFileChange={handleFileChange}
            onNativeCapture={handleNativeCapture}
            nativeAvailable={isNativeAvailable()}
            targetApp={settings.targetApp}
            answerSource={settings.answerSource}
            floatingSessionRunning={floatingSessionRunning}
            floatingSessionPreparing={floatingSessionPreparing}
            floatingCapabilities={floatingCapabilities}
            onOpenTarget={() => {
              const targetLabel = targetAppLabel(settings.targetApp);
              if (settings.targetApp === 'auto') {
                notify('悬浮会话会识别当前前台题目页');
              } else if (openTargetApp(settings.targetApp)) {
                addLog(`已请求打开${targetLabel}。`, 'info');
                notify(`请切到${targetLabel}题目页`);
              } else {
                notify(`未找到${targetLabel}，请先安装或手动打开`);
              }
            }}
            onRealAnalyze={handleRealAnalyze}
            onRetry={handleRetry}
            onModeChange={handleModeChange}
            onStart={handleStart}
            onPause={handlePause}
            onResume={handleResume}
            onStop={handleStop}
            onExecute={() => executeQuestion('manual')}
            onManualAdvance={handleManualAdvance}
          />
        )}
        {view === 'settings' && (
          <SettingsView
            settings={settings}
            serverConfig={serverConfig}
            connection={connection}
            testBusy={testBusy}
            canEdit={canEditSettings}
            onChange={handleSettingsChange}
            onSave={handleSaveSettings}
            onResetPrompt={handleResetPrompt}
            onTest={handleTestConnection}
          />
        )}
        {view === 'bank' && <QuestionBankView notify={notify} />}
        {view === 'history' && <HistoryView logs={logs} run={run} onReset={handleReset} />}
      </main>

      {toast && <div className="toast"><CheckCircle2 size={16} /> {toast}</div>}
    </div>
  );
}

function RunView({
  run, question, phase, error, logs, isBusy, uploadedImage, uploadedName,
  onFileChange, onNativeCapture, nativeAvailable, targetApp, answerSource, floatingSessionRunning, floatingSessionPreparing, floatingCapabilities, onOpenTarget, onRealAnalyze, onRetry, onModeChange, onStart, onPause, onResume, onStop,
  onExecute, onManualAdvance,
}) {
  const answerSet = new Set(question.answer_indices || []);
  const phaseLabel = {
    ready: '准备就绪',
    capturing: '正在截取页面',
    recognizing: '模型识别中',
    executing: '模拟点击中',
    waiting_page_change: '等待页面变化',
    paused: '已暂停',
    stopped: '已停止',
    error: '需要处理',
  }[phase] || phase;

  return (
    <div className="page-content">
      <section className="page-heading page-heading-run">
        <div>
          <div className="eyebrow"><span className="eyebrow-line" /> DAILY QUESTION RUNNER</div>
          <h1>运行台<span className="heading-slash">/</span><em>实时控制</em></h1>
          <p>截图识别、答案提示和点击动作集中在一块安静的工作台里。</p>
        </div>
        <div className="heading-status"><span className={`status-dot status-dot-${floatingSessionRunning || floatingSessionPreparing || run.status === 'active' ? 'live' : 'muted'}`} /> <span>{floatingSessionRunning ? '悬浮会话已启用' : floatingSessionPreparing ? '正在准备悬浮会话' : statusLabel(run.status)}</span><small>SESSION #{String(run.questionNo).padStart(3, '0')}</small></div>
      </section>

      <section className="run-toolbar">
        <div className="mode-switch" role="group" aria-label="刷题模式">
          <button className={run.mode === 'auto' ? 'mode-active' : ''} onClick={() => onModeChange('auto')}><Zap size={15} /> 自动刷题</button>
          <button className={run.mode === 'manual' ? 'mode-active' : ''} onClick={() => onModeChange('manual')}><CircleHelp size={15} /> 手动辅助</button>
          <button className={run.mode === 'capture' ? 'mode-active' : ''} onClick={() => onModeChange('capture')}><ScanLine size={15} /> 采集模式</button>
        </div>
        <div className="toolbar-divider" />
        <div className="toolbar-note"><span className="status-dot status-dot-live" /> {answerSource === 'question_bank' ? '题库匹配：模型提取题干，本地显示答案' : nativeAvailable ? <><span>悬浮窗 {floatingCapabilities.overlay ? '已就绪' : '待开启'}</span><span> · 截图 {floatingCapabilities.capture ? '已就绪' : '待授权'}</span><span> · 自动点击 {floatingCapabilities.gesture ? '已就绪' : '待开启'}</span></> : (run.mode === 'auto' ? '持续自动执行，直到手动停止' : '只提示答案，点击由你完成')}</div>
        <div className="toolbar-actions">
          {targetApp !== 'auto' && nativeAvailable && !floatingSessionRunning && !floatingSessionPreparing && <button className="button button-quiet" onClick={onOpenTarget}><ExternalLink size={15} /> 打开{targetAppLabel(targetApp)}</button>}
          {nativeAvailable ? (
            floatingSessionRunning || floatingSessionPreparing
              ? <button className="button button-quiet" onClick={onStop}><Square size={15} /> {floatingSessionPreparing ? '取消准备' : '停止悬浮识题'}</button>
              : <button className="button button-primary" onClick={onStart}><Play size={15} /> 启用悬浮识题</button>
          ) : run.status === 'active' ? (
            <button className="button button-quiet" onClick={onPause}><Pause size={15} /> 暂停</button>
          ) : run.status === 'paused' ? (
            <button className="button button-primary" onClick={onResume} disabled={isBusy}><Play size={15} /> {isBusy ? '检查题目页…' : '继续'}</button>
          ) : (
            <button className="button button-primary" onClick={onStart}><Play size={15} /> 开始运行</button>
          )}
          {!nativeAvailable && (run.status === 'active' || run.status === 'paused') ? <button className="icon-button danger-button" onClick={onStop} aria-label="停止运行"><Square size={15} /></button> : null}
        </div>
      </section>

      <section className="overview-grid">
        <div className="progress-panel panel-dark">
          <div className="panel-topline"><span>CONTINUOUS RUN</span><span className="mono">已完成 {run.answered.toString().padStart(3, '0')} 题</span></div>
          <div className="progress-content">
            <div className="progress-ring" style={{ '--progress': '360deg' }}>
              <div><strong>{run.answered}</strong><span>题完成</span></div>
            </div>
            <div className="progress-copy">
              <div className="progress-kicker">CURRENT QUESTION</div>
              <div className="progress-number">{String(run.questionNo).padStart(2, '0')}<span>题</span></div>
              <div className="progress-meta"><span className="signal-bars"><i /><i /><i /><i /></span> {phaseLabel}</div>
            </div>
          </div>
          <div className="progress-track"><span style={{ width: run.status === 'active' ? '100%' : '0%' }} /></div>
          <div className="panel-footnote"><Clock3 size={13} /> 持续运行，手动停止 <span>·</span> {modeLabel(run.mode)}</div>
        </div>

        <div className="answer-panel panel-light">
          <div className="answer-panel-top">
            <span className="answer-panel-label">{answerSource === 'question_bank' ? 'QUESTION BANK READOUT' : 'MODEL READOUT'}</span>
            <div className="answer-readout">
              <span className="confidence"><span className="status-dot status-dot-live" /> {question.match_label || `${Math.round((question.confidence || 0) * 100)}% CONFIDENCE`}</span>
              <div className="answer-chips" aria-label="模型答案">
                {(question.answer_indices || []).map((index) => <span className="answer-chip" key={index}><Check size={14} /> {optionFor(question, index)?.text || `选项 ${index + 1}`}</span>)}
                {question.answer_text && <span className="answer-chip"><Check size={14} /> {question.answer_text}</span>}
              </div>
            </div>
          </div>
          <div className="answer-head"><div className="type-tag">{getQuestionTypeLabel(question.question_type)}</div><span className="mono">Q{String(question.question_no).padStart(3, '0')}</span></div>
          <h2>{question.question}</h2>
          <div className="answer-footer"><span><Bot size={14} /> {question.match_source ? `来源：${question.match_source.file || question.match_source}` : '选择答案后点击确认'}</span><span className="answer-footer-arrow"><ArrowRight size={15} /></span></div>
        </div>
      </section>

      <section className="workspace-grid">
        <div className="screen-panel panel-light">
          <div className="panel-title-row"><div><span className="panel-index">01</span><div><h3>当前页面</h3><p>SCREEN CAPTURE / MOCK DEVICE</p></div></div><div className="screen-actions"><button className="upload-button" onClick={onNativeCapture} disabled={!nativeAvailable || isBusy} title={nativeAvailable ? '请求 Android MediaProjection 截图' : 'Android 原生壳中启用'}><ScanLine size={14} /> 原生截图</button><label className="upload-button"><Upload size={14} /> 上传截图<input type="file" accept="image/*" onChange={onFileChange} /></label>{uploadedImage && <span className="upload-name" title={uploadedName}><FileImage size={13} /> {uploadedName}</span>}</div></div>
          <div className="phone-stage">
            <div className="phone-frame">
              <div className="phone-status"><span>9:41</span><span>● ● ▮</span></div>
              <div className="mini-program-bar"><span className="mini-back">‹</span><strong>每日答题</strong><span className="mini-more">•••</span></div>
              <div className="mini-progress"><span>连续答题</span><strong>第 {run.questionNo} 题</strong><div><i style={{ width: '100%' }} /></div></div>
              <div className="mini-question"><span className="mini-label">{getQuestionTypeLabel(question.question_type)}</span><h4>{question.question}</h4></div>
              <div className="mini-options">
                {question.options.map((option) => <div className={`mini-option ${answerSet.has(option.index) ? 'mini-option-answer' : ''}`} key={option.index}><span className="mini-option-index">{String.fromCharCode(65 + option.index)}</span><span>{option.text}</span>{answerSet.has(option.index) && <Check size={13} />}</div>)}
              </div>
                <div className="mini-next">确认 <ArrowRight size={13} /></div>
              <div className="mini-watermark">PREVIEW / SCREEN 01</div>
            </div>
            <div className="capture-callout callout-top"><ScanLine size={13} /> OCR + VISION</div>
            <div className="capture-callout callout-bottom"><span className="status-dot status-dot-live" /> FRAME LOCKED</div>
          </div>
          <div className="screen-footer"><span><ShieldCheck size={14} /> 截图只在本地代理转发</span><span className="mono">{uploadedImage ? 'UPLOADED IMAGE' : 'MOCK FRAME'} · 1080 × 1920</span></div>
        </div>

        <div className="execution-panel panel-light">
          <div className="panel-title-row"><div><span className="panel-index">02</span><div><h3>执行队列</h3><p>ACTION PIPELINE</p></div></div><div className={`phase-badge phase-${phase}`}><span className="status-dot" /> {phaseLabel}</div></div>
          <div className="pipeline">
            <PipelineStep index="01" icon={FileImage} label="截取当前页面" state={['capturing', 'recognizing', 'executing'].includes(phase) ? 'done' : 'ready'} detail="MediaProjection / mock" />
            <PipelineStep index="02" icon={Bot} label="模型识别 JSON" state={['recognizing', 'executing'].includes(phase) ? 'active' : phase === 'ready' || phase === 'waiting_page_change' ? 'done' : 'ready'} detail={`${question.confidence * 100}% confidence`} />
            <PipelineStep index="03" icon={Zap} label={run.mode === 'auto' ? '执行点击选项' : '等待手动选择'} state={phase === 'executing' ? 'active' : phase === 'waiting_page_change' ? 'done' : 'ready'} detail={run.mode === 'auto' ? (nativeAvailable ? 'AccessibilityService' : 'preview fixture') : '用户操作'} />
            <PipelineStep index="04" icon={ArrowRight} label="确认并等待页面变化" state={phase === 'waiting_page_change' ? 'active' : 'ready'} detail="点击确认 / 检测新页面" />
          </div>
          {error && <div className="inline-error"><AlertTriangle size={15} /><span>{error}</span><button onClick={onRetry}>重试</button></div>}
          <div className="execution-actions">
            {run.mode === 'manual' && run.status === 'active' && <button className="button button-primary full-button" onClick={onManualAdvance} disabled={isBusy}><Check size={15} /> 我已完成本题，识别下一题</button>}
            {run.mode === 'auto' && run.status === 'active' && <button className="button button-quiet full-button" onClick={onExecute} disabled={isBusy}><Zap size={15} /> {isBusy ? '执行中…' : '立即执行当前题'}</button>}
          </div>
          <div className="real-model-row"><div><strong>真实模型识别</strong><span>{uploadedImage ? '已载入截图，可发送到本地代理' : '上传截图后启用'}</span></div><button className="icon-text-button" onClick={onRealAnalyze} disabled={!uploadedImage || isBusy}><Bot size={14} /> 识别截图</button></div>
        </div>
      </section>

      <section className="lower-grid">
        <div className="log-panel panel-light"><div className="panel-title-row compact"><div><span className="panel-index">03</span><div><h3>运行日志</h3><p>EVENT STREAM / MASKED</p></div></div><Terminal size={16} /></div><div className="log-list">{logs.map((log, index) => <div className="log-row" key={`${log.time}-${index}`}><span className={`log-bullet log-${log.tone}`} /><time>{log.time}</time><p>{log.text}</p></div>)}</div></div>
        <div className="guard-panel panel-dark"><div className="panel-title-row compact"><div><span className="panel-index">04</span><div><h3>运行护栏</h3><p>GUARDRAILS</p></div></div><LockKeyhole size={16} /></div><div className="guard-list"><GuardItem icon={ShieldCheck} title="Key 不进入日志" detail="本机保存，日志仅显示掩码" /><GuardItem icon={ScanLine} title="JSON 先校验再执行" detail="选项编号与题型双重检查" /><GuardItem icon={Clock3} title="持续自动运行" detail="手动停止或出错时才暂停" /></div></div>
      </section>
    </div>
  );
}

function PipelineStep({ index, icon: Icon, label, detail, state }) {
  return <div className={`pipeline-step pipeline-${state}`}><div className="pipeline-index">{state === 'done' ? <Check size={13} /> : index}</div><div className="pipeline-icon"><Icon size={16} /></div><div className="pipeline-copy"><strong>{label}</strong><span>{detail}</span></div><div className="pipeline-state">{state === 'active' ? <span className="loader-dot" /> : state === 'done' ? 'DONE' : 'WAIT'}</div></div>;
}

function GuardItem({ icon: Icon, title, detail }) {
  return <div className="guard-item"><div className="guard-icon"><Icon size={15} /></div><div><strong>{title}</strong><span>{detail}</span></div></div>;
}

function SettingsView({ settings, serverConfig, connection, testBusy, canEdit, onChange, onSave, onResetPrompt, onTest }) {
  return <div className="page-content settings-page">
    <section className="page-heading"><div><div className="eyebrow"><span className="eyebrow-line" /> PROVIDER CONTROL</div><h1>模型设置<span className="heading-slash">/</span><em>可调参数</em></h1><p>所有运行配置都在本机保存，运行时会生成一份不可变快照。</p></div><div className="settings-lock"><LockKeyhole size={15} /> {canEdit ? '可编辑' : '运行中锁定'}</div></section>
    <section className="settings-grid">
      <div className="settings-main panel-light">
        <div className="panel-title-row"><div><span className="panel-index">01</span><div><h3>Provider 连接</h3><p>OPENAI-COMPATIBLE ENDPOINT</p></div></div><div className={`connection-badge badge-${connection}`}><span className="status-dot" /> {connection === 'ready' ? 'READY' : connection === 'testing' ? 'TESTING' : connection === 'missing' ? 'KEY REQUIRED' : 'LOCAL PROXY'}</div></div>
        <div className="field-grid"><Field label="API URL" hint="支持 base URL 或 /chat/completions" value={settings.endpoint} onChange={(value) => onChange('endpoint', value)} disabled={!canEdit} wide /><Field label="模型名称" hint="当前预设来自本地环境" value={settings.model} onChange={(value) => onChange('model', value)} disabled={!canEdit} /><label className="field-block"><span>答案来源</span><select value={settings.answerSource} onChange={(event) => onChange('answerSource', event.target.value)} disabled={!canEdit}><option value="vision">视觉模型直接解题</option><option value="question_bank">本地题库匹配</option></select><small>题库提供答案，视觉模型定位当前选项；支持自动模式。</small></label><label className="field-block"><span>题目目标</span><select value={settings.targetApp} onChange={(event) => onChange('targetApp', event.target.value)} disabled={!canEdit}><option value="auto">自动识别当前题目页</option><option value="wechat">微信题库（优先）</option><option value="simulator">题库模拟器（优先）</option></select><small>默认跟随当前前台题目页，不会强制打开微信。</small></label><label className="field-block"><span>起始题号</span><input type="number" min="1" max="9999" value={settings.startQuestionNo || 1} onChange={(event) => onChange('startQuestionNo', Number(event.target.value) || 1)} disabled={!canEdit} /><small>采集模式从这一题开始编号，默认 1。</small></label><div className="field-block"><label>API Key <span>本机保存</span></label><div className="secret-field"><KeyRound size={15} /><input type="password" value={settings.apiKey || ''} placeholder={serverConfig.keyConfigured ? '本地代理已配置，留空即可' : '粘贴模型 Key'} onChange={(event) => onChange('apiKey', event.target.value)} disabled={!canEdit} /><span className="secret-mask">{settings.apiKey ? maskSecret(settings.apiKey) : serverConfig.keyConfigured ? 'ENV ••••' : '—'}</span></div><small>Key 保存在本机应用数据中，页面日志保持脱敏。</small></div></div>
        <div className="settings-actions"><button className="button button-primary" onClick={onSave} disabled={!canEdit}><Save size={15} /> 保存配置</button><button className="button button-quiet" onClick={onTest} disabled={testBusy}><Wifi size={15} /> {testBusy ? '测试中…' : '测试连接'}</button></div>
      </div>
      <div className="settings-side panel-dark"><div className="panel-title-row compact"><div><span className="panel-index">02</span><div><h3>运行策略</h3><p>SESSION BEHAVIOR</p></div></div><Activity size={16} /></div><div className="strategy-card"><div className="strategy-icon"><Zap size={17} /></div><div><strong>自动刷题</strong><span>识别、核对选中状态、翻题；按开关决定是否最终提交。</span></div><button className={`toggle ${settings.mode === 'auto' ? 'toggle-on' : ''}`} onClick={() => onChange('mode', settings.mode === 'auto' ? 'manual' : 'auto')} disabled={!canEdit} aria-label="切换自动模式"><i /></button></div><div className="strategy-card"><div className="strategy-icon"><CircleHelp size={17} /></div><div><strong>手动辅助</strong><span>只展示答案，下一题由你点击。</span></div><button className={`toggle ${settings.mode === 'manual' ? 'toggle-on' : ''}`} onClick={() => onChange('mode', settings.mode === 'manual' ? 'auto' : 'manual')} disabled={!canEdit} aria-label="切换手动模式"><i /></button></div><div className="strategy-card"><div className="strategy-icon"><ScanLine size={17} /></div><div><strong>采集模式</strong><span>只截图识别不答题；点悬浮球开始/停止，跳题靠你摆放的“跳”按钮。</span></div><button className={`toggle ${settings.mode === 'capture' ? 'toggle-on' : ''}`} onClick={() => onChange('mode', settings.mode === 'capture' ? 'manual' : 'capture')} disabled={!canEdit} aria-label="切换采集模式"><i /></button></div><label className="field-block"><span>翻题方式</span><select value={settings.navigation || 'auto'} onChange={(e) => onChange('navigation', e.target.value)} disabled={!canEdit}><option value="auto">点击确认 / 下一题按钮</option><option value="left">无按钮时向左滑</option><option value="right">无按钮时向右滑</option><option value="up">无按钮时向上滑</option><option value="down">无按钮时向下滑</option></select></label>
        <label className="field-block"><span>页面等待时间（毫秒）</span><input type="number" min="500" max="10000" value={settings.settleMs || 1000} onChange={(e) => onChange('settleMs', Number(e.target.value))} disabled={!canEdit} /></label>
        <label className="field-block"><span><input type="checkbox" checked={settings.autoSubmit === true} onChange={(e) => onChange('autoSubmit', e.target.checked)} disabled={!canEdit} /> 最后自动提交</span><small>仅在明确识别末题或答题卡、页面显示未答题数为 0 或已逐题核实全部题号时交卷；随后核对提交结果。</small></label>
        <label className="field-block"><span><input type="checkbox" checked={settings.bankFallback !== false} onChange={(e) => onChange('bankFallback', e.target.checked)} disabled={!canEdit} /> 题库未命中时由模型解题</span></label>
        <label className="field-block"><span>自动模式补充要求</span><textarea value={settings.automationInstructions || ''} onChange={(e) => onChange('automationInstructions', e.target.value)} disabled={!canEdit} placeholder="例如：题目位于正文区域，底部固定栏为导航" /></label>
        <div className="strategy-note"><ShieldCheck size={14} /> 自动模式不设置固定题数上限。每次动作后重新识别，长题收集多屏；不确定时暂停。</div></div>
      <div className="prompt-panel panel-light"><div className="panel-title-row"><div><span className="panel-index">03</span><div><h3>提示词编辑器</h3><p>VISION / QUESTION BANK CONTRACT</p></div></div><div className="prompt-actions"><button className="icon-text-button prompt-save-button" onClick={onSave} disabled={!canEdit}><Save size={14} /> 保存提示词</button><button className="icon-text-button" onClick={() => onResetPrompt(settings.answerSource)} disabled={!canEdit}><RotateCcw size={14} /> 恢复当前默认</button></div></div><label className="prompt-label">视觉解题提示词</label><textarea className="prompt-editor" value={settings.prompt} onChange={(event) => onChange('prompt', event.target.value)} disabled={!canEdit} spellCheck="false" /><div className="prompt-footer"><span><Code2 size={14} /> 支持 {`{QUESTION_IMAGE}`}、{`{QUESTION_NO}`} 变量</span><span className="mono">{settings.prompt.length} chars</span></div><label className="prompt-label">题库匹配提示词</label><textarea className="prompt-editor" value={settings.questionBankPrompt} onChange={(event) => onChange('questionBankPrompt', event.target.value)} disabled={!canEdit} spellCheck="false" /><div className="prompt-footer"><span><Code2 size={14} /> 只返回 question JSON</span><span className="mono">{settings.questionBankPrompt.length} chars</span></div></div>
      <div className="settings-side panel-paper"><div className="panel-title-row compact"><div><span className="panel-index">04</span><div><h3>请求参数</h3><p>RETRY & TIMEOUT</p></div></div><Clock3 size={16} /></div><div className="number-fields"><label>超时（毫秒）<input type="number" min="5000" max="120000" step="1000" value={settings.timeoutMs} onChange={(event) => onChange('timeoutMs', event.target.value)} disabled={!canEdit} /></label><label>重试次数<input type="number" min="0" max="5" value={settings.retryCount} onChange={(event) => onChange('retryCount', event.target.value)} disabled={!canEdit} /></label></div><div className="request-note"><AlertTriangle size={14} /> 接口返回内容会先经过 JSON Schema 校验。</div></div>
    </section>
  </div>;
}

function Field({ label, hint, value, onChange, disabled, wide }) {
  return <label className={`field-block ${wide ? 'field-wide' : ''}`}><span>{label}</span><input value={value} onChange={(event) => onChange(event.target.value)} disabled={disabled} /><small>{hint}</small></label>;
}

function HistoryView({ logs, run, onReset }) {
  return <div className="page-content history-page"><section className="page-heading"><div><div className="eyebrow"><span className="eyebrow-line" /> SESSION ARCHIVE</div><h1>运行记录<span className="heading-slash">/</span><em>可追溯</em></h1><p>每一轮运行的状态、题号和模型动作都在这里留下轻量记录。</p></div><button className="button button-primary" onClick={onReset}><RotateCcw size={15} /> 新建运行</button></section><section className="history-summary"><div className="summary-stat"><span>当前状态</span><strong>{statusLabel(run.status)}</strong><small>{modeLabel(run.mode)}</small></div><div className="summary-stat"><span>已完成</span><strong>{run.answered}</strong><small>question count</small></div><div className="summary-stat"><span>最后题号</span><strong>Q{String(run.questionNo).padStart(3, '0')}</strong><small>session cursor</small></div></section><section className="archive-list panel-light"><div className="panel-title-row"><div><span className="panel-index">01</span><div><h3>最近事件</h3><p>LOCAL EVENT STREAM</p></div></div><Terminal size={16} /></div>{logs.map((log, index) => <div className="archive-row" key={`${log.time}-${index}`}><span className={`log-bullet log-${log.tone}`} /><time>{log.time}</time><strong>{log.tone.toUpperCase()}</strong><p>{log.text}</p><ChevronRight size={15} /></div>)}</section></div>;
}

const BANK_PAGE_SIZE = 20;
const BANK_TYPES = [
  { value: 'single', label: '单选题' },
  { value: 'multiple', label: '多选题' },
  { value: 'indefinite', label: '不定项选择题' },
  { value: 'true_false', label: '判断题' },
  { value: 'fill_blank', label: '填空题' },
];

function emptyBankRecord() {
  return {
    id: '',
    question: '',
    type: 'single',
    options: [{ text: '' }, { text: '' }],
    answer_indices: [],
    answer_text: '',
  };
}

function bankTypeLabel(type) {
  return BANK_TYPES.find((item) => item.value === type)?.label || type;
}

function bankAnswerLabel(record) {
  const indices = Array.isArray(record.answer_indices) ? record.answer_indices : [];
  if (indices.length) return indices.map((index) => String.fromCharCode(65 + index)).join('、');
  return record.answer_text || '—';
}

function QuestionBankView({ notify }) {
  const available = isQuestionBankAvailable();
  const [query, setQuery] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [offset, setOffset] = useState(0);
  const [total, setTotal] = useState(0);
  const [records, setRecords] = useState([]);
  const [loading, setLoading] = useState(false);
  const [draft, setDraft] = useState(null);
  const [busy, setBusy] = useState(false);
  const fileRef = useRef(null);

  const load = useCallback(async (nextOffset = 0, nextQuery = '') => {
    if (!available) return;
    setLoading(true);
    try {
      const result = await listQuestionBank({ query: nextQuery, offset: nextOffset, limit: BANK_PAGE_SIZE });
      if (!result) {
        setRecords([]);
        setTotal(0);
        return;
      }
      setRecords(Array.isArray(result.records) ? result.records : []);
      setTotal(Number(result.total) || 0);
      setOffset(Number(result.offset) || 0);
    } catch (error) {
      notify?.(error.message || '题库读取失败');
    } finally {
      setLoading(false);
    }
  }, [available, notify]);

  useEffect(() => {
    if (!available) return undefined;
    load(0, appliedQuery);
    return undefined;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [appliedQuery, available]);

  useEffect(() => {
    if (!available) return undefined;
    window.onNativeQuestionBankExport = (payload) => {
      let parsed = {};
      try { parsed = JSON.parse(String(payload || '{}')); } catch { parsed = {}; }
      notify?.(parsed.ok ? `题库已导出：${parsed.path}` : '题库导出失败');
    };
    return () => {
      if (window.onNativeQuestionBankExport) window.onNativeQuestionBankExport = null;
    };
  }, [available, notify]);

  const startCreate = () => setDraft(emptyBankRecord());

  const startEdit = (record) => setDraft({
    id: record.id,
    question: record.question,
    type: record.type,
    options: (record.options || []).map((option) => ({ text: option.text })),
    answer_indices: [...(record.answer_indices || [])],
    answer_text: record.answer_text || '',
  });

  const save = async () => {
    if (!draft) return;
    if (!String(draft.question || '').trim()) {
      notify?.('题干不能为空');
      return;
    }
    setBusy(true);
    try {
      const record = {
        id: draft.id || '',
        question: String(draft.question).trim(),
        type: draft.type,
        options: draft.options
          .map((option) => ({ text: String(option.text || '').trim() }))
          .filter((option) => option.text)
          .map((option, index) => ({ index, text: option.text })),
        answer_indices: [...draft.answer_indices].sort((a, b) => a - b),
        answer_text: String(draft.answer_text || '').trim(),
        anchors: [],
        sources: [],
      };
      const result = await saveQuestionBankRecord(record);
      if (!result?.ok) throw new Error(result?.error || '保存失败');
      notify?.('已保存到题库');
      setDraft(null);
      await load(offset, appliedQuery);
    } catch (error) {
      notify?.(error.message || '保存失败');
    } finally {
      setBusy(false);
    }
  };

  const remove = async (record) => {
    if (!window.confirm('确定删除这条题目？')) return;
    try {
      const result = await deleteQuestionBankRecord(record.id);
      if (!result?.ok) throw new Error('删除失败');
      notify?.('已删除');
      await load(offset, appliedQuery);
    } catch (error) {
      notify?.(error.message || '删除失败');
    }
  };

  const handleImportFile = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    try {
      const content = await file.text();
      const result = await importQuestionBank(content);
      if (!result?.ok) throw new Error(result?.error || '导入失败');
      notify?.(`已导入 ${result.count} 条题目`);
      setAppliedQuery('');
      setQuery('');
      await load(0, '');
    } catch (error) {
      notify?.(error.message || '导入失败');
    }
  };

  const toggleAnswer = (index) => {
    setDraft((current) => {
      if (!current) return current;
      const selected = new Set(current.answer_indices || []);
      if (selected.has(index)) selected.delete(index); else selected.add(index);
      return { ...current, answer_indices: [...selected].sort((a, b) => a - b) };
    });
  };

  if (!available) {
    return (
      <div className="page-content">
        <section className="page-heading"><div><div className="eyebrow"><span className="eyebrow-line" /> QUESTION BANK</div><h1>题库<span className="heading-slash">/</span><em>本地管理</em></h1><p>题库管理需要在本机安装的练题流 App 中使用。</p></div></section>
      </div>
    );
  }

  const pageCount = Math.max(1, Math.ceil(total / BANK_PAGE_SIZE));
  const pageIndex = Math.floor(offset / BANK_PAGE_SIZE) + 1;

  return (
    <div className="page-content">
      <section className="page-heading">
        <div>
          <div className="eyebrow"><span className="eyebrow-line" /> QUESTION BANK</div>
          <h1>题库<span className="heading-slash">/</span><em>本地管理</em></h1>
          <p>共 {total} 条可匹配题目。答题时优先查这里；未命中或不唯一会走视觉兜底并把结果写回。</p>
        </div>
      </section>

      <section className="run-toolbar bank-toolbar">
        <div className="bank-search">
          <input
            value={query}
            placeholder="搜索题干…"
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => { if (event.key === 'Enter') setAppliedQuery(query.trim()); }}
          />
          <button className="button button-quiet" onClick={() => setAppliedQuery(query.trim())}><Search size={15} /> 搜索</button>
        </div>
        <div className="toolbar-actions">
          <button className="button button-primary" onClick={startCreate}><Plus size={15} /> 新增</button>
          <button className="button button-quiet" onClick={() => exportQuestionBank() && notify?.('导出中…')}><Download size={15} /> 导出</button>
          <button className="button button-quiet" onClick={() => fileRef.current?.click()}><Upload size={15} /> 导入</button>
          <input ref={fileRef} type="file" accept="application/json,.json" style={{ display: 'none' }} onChange={handleImportFile} />
        </div>
      </section>

      {draft && (
        <section className="panel-light bank-editor">
          <div className="panel-title-row"><div><span className="panel-index">EDIT</span><div><h3>{draft.id ? '编辑题目' : '新增题目'}</h3><p>QUESTION EDITOR</p></div></div></div>
          <label className="prompt-label">题干</label>
          <textarea className="prompt-editor" style={{ minHeight: 90 }} value={draft.question} onChange={(event) => setDraft({ ...draft, question: event.target.value })} spellCheck="false" />
          <label className="field-block"><span>题型</span>
            <select value={draft.type} onChange={(event) => setDraft({ ...draft, type: event.target.value })}>
              {BANK_TYPES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
            </select>
          </label>
          <label className="prompt-label">选项（勾选=正确答案）</label>
          {draft.options.map((option, index) => (
            <div className="bank-option-row" key={index}>
              <input type="checkbox" checked={(draft.answer_indices || []).includes(index)} onChange={() => toggleAnswer(index)} />
              <span className="bank-option-letter">{String.fromCharCode(65 + index)}</span>
              <input
                value={option.text}
                placeholder={`选项 ${String.fromCharCode(65 + index)}`}
                onChange={(event) => {
                  const options = [...draft.options];
                  options[index] = { text: event.target.value };
                  setDraft({ ...draft, options });
                }}
              />
              <button className="icon-button" aria-label="删除选项" onClick={() => setDraft({ ...draft, options: draft.options.filter((_, i) => i !== index), answer_indices: [] })}><Trash2 size={15} /></button>
            </div>
          ))}
          <button className="button button-quiet" onClick={() => setDraft({ ...draft, options: [...draft.options, { text: '' }] })}><Plus size={15} /> 加选项</button>
          <label className="field-block"><span>填空题答案（非填空可留空）</span>
            <input value={draft.answer_text} onChange={(event) => setDraft({ ...draft, answer_text: event.target.value })} />
          </label>
          <div className="toolbar-actions" style={{ marginTop: 12 }}>
            <button className="button button-primary" onClick={save} disabled={busy}><Save size={15} /> {busy ? '保存中…' : '保存'}</button>
            <button className="button button-quiet" onClick={() => setDraft(null)} disabled={busy}><X size={15} /> 取消</button>
          </div>
        </section>
      )}

      <section className="archive-list panel-light bank-list">
        <div className="panel-title-row"><div><span className="panel-index">01</span><div><h3>题目列表</h3><p>{loading ? 'LOADING' : `第 ${pageIndex} / ${pageCount} 页`}</p></div></div><Database size={16} /></div>
        {records.map((record) => (
          <div className="bank-row" key={record.id}>
            <span className="log-bullet log-info" />
            <strong className="bank-answer">{bankAnswerLabel(record)}</strong>
            <p className="bank-question">{record.question}</p>
            <small className="bank-type">{bankTypeLabel(record.type)}</small>
            <div className="bank-actions">
              <button className="icon-text-button" onClick={() => startEdit(record)}><Code2 size={14} /> 编辑</button>
              <button className="icon-button danger-button" onClick={() => remove(record)} aria-label="删除"><Trash2 size={15} /></button>
            </div>
          </div>
        ))}
        {!records.length && !loading && <div className="bank-row"><p className="bank-question">没有匹配的题目。</p></div>}
      </section>

      <section className="run-toolbar">
        <button className="button button-quiet" disabled={offset <= 0 || loading} onClick={() => load(Math.max(0, offset - BANK_PAGE_SIZE), appliedQuery)}>上一页</button>
        <div className="toolbar-note">共 {total} 条 · 第 {pageIndex} / {pageCount} 页</div>
        <button className="button button-quiet" disabled={offset + BANK_PAGE_SIZE >= total || loading} onClick={() => load(offset + BANK_PAGE_SIZE, appliedQuery)}>下一页</button>
      </section>
    </div>
  );
}

export default App;

const QUESTION_ACTIONS = {
  single: 'tap_answer_then_confirm',
  true_false: 'tap_answer_then_confirm',
  multiple: 'tap_answers_then_confirm',
  indefinite: 'tap_answers_then_confirm',
  fill_blank: 'show_answer_only',
};

function normalizeBox(box, fieldName) {
  if (box === null || box === undefined) return null;
  if (!Array.isArray(box) || box.length < 4) throw new Error(`${fieldName} 坐标格式无效`);
  const normalized = box.slice(0, 4).map(Number);
  const [x, y, width, height] = normalized;
  if (
    normalized.some((value) => !Number.isFinite(value))
    || x < 0 || y < 0 || width <= 0 || height <= 0
    || x + width > 100 || y + height > 100
  ) {
    throw new Error(`${fieldName} 坐标必须位于 0 到 100 之间`);
  }
  return normalized;
}

export function getQuestionAction(questionType) {
  return QUESTION_ACTIONS[questionType] || 'manual_review';
}

export function createRunState({ mode = 'manual' } = {}) {
  return {
    mode: mode === 'auto' ? 'auto' : 'manual',
    questionNo: 1,
    status: 'idle',
    answered: 0,
  };
}

export function startRun(state) {
  if (!state) return state;
  return { ...state, status: 'active' };
}

export function pauseRun(state) {
  if (!state || !['active', 'waiting_page_change'].includes(state.status)) return state;
  return { ...state, status: 'paused' };
}

export function resumeRun(state) {
  if (!state || state.status !== 'paused') return state;
  return { ...state, status: 'active' };
}

export function stopRun(state) {
  if (!state) return state;
  return { ...state, status: 'stopped' };
}

export function advanceRun(state, questionType) {
  if (!state || !['active', 'waiting_page_change'].includes(state.status)) return state;

  return {
    ...state,
    answered: state.answered + 1,
    questionNo: state.questionNo + 1,
    status: 'active',
    lastAction: getQuestionAction(questionType),
  };
}

export function parseModelResult(raw) {
  let text = typeof raw === 'string' ? raw.trim() : raw;
  if (typeof text === 'string') {
    text = text.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '').trim();
    try {
      text = JSON.parse(text);
    } catch {
      throw new Error('模型响应不是有效 JSON');
    }
  }

  if (!text || typeof text !== 'object') throw new Error('模型响应缺少对象内容');
  const type = text.question_type;
  if (!['single', 'multiple', 'indefinite', 'true_false', 'fill_blank'].includes(type)) {
    throw new Error('question_type 缺少或值不受支持');
  }
  const matchStatus = text.match_status;
  const allowEmpty = type === 'fill_blank' || Boolean(matchStatus);
  if (!Array.isArray(text.options) || (!allowEmpty && text.options.length === 0)) {
    throw new Error('options 缺少或为空');
  }
  if (!Array.isArray(text.answer_indices) || (!allowEmpty && text.answer_indices.length === 0)) {
    throw new Error('answer_indices 缺少或为空');
  }

  const options = text.options.map((option) => {
    const optionIndex = Number(option?.index);
    if (!Number.isInteger(optionIndex) || optionIndex < 0) {
      throw new Error('options 包含无效选项编号');
    }
    const box = normalizeBox(option?.box, 'option.box');
    return {
      index: optionIndex,
      text: String(option?.text || ''),
      box,
    };
  });
  const optionIndexes = new Set(options.map((option) => option.index));
  if (optionIndexes.size !== options.length) throw new Error('options 包含重复选项编号');
  const answerIndices = text.answer_indices.map(Number);
  if (answerIndices.some((index) => !Number.isInteger(index) || !optionIndexes.has(index))) {
    throw new Error('answer_indices 包含无效选项编号');
  }
  const uniqueAnswerIndices = [...new Set(answerIndices)];
  if (!allowEmpty && ['single', 'true_false'].includes(type) && uniqueAnswerIndices.length !== 1) {
    throw new Error(`${type} 题必须只有一个答案`);
  }

  const confidence = Number(text.confidence);
  return {
    question_no: Number.isFinite(Number(text.question_no)) ? Number(text.question_no) : null,
    question_type: type,
    question: String(text.question || ''),
    options,
    answer_indices: uniqueAnswerIndices,
    confirm_box: normalizeBox(text.confirm_box, 'confirm_box'),
    next_box: normalizeBox(text.next_box, 'next_box'),
    confidence: Number.isFinite(confidence) ? Math.min(Math.max(confidence, 0), 1) : 0,
    answer_text: String(text.answer_text || ''),
    match_status: matchStatus ? String(matchStatus) : null,
    match_label: text.match_label ? String(text.match_label) : null,
    match_source: text.match_source ? String(text.match_source) : null,
    next_action: text.next_action || getQuestionAction(type),
  };
}

export function getActionPlan(question) {
  const answerIndices = Array.isArray(question?.answer_indices)
    ? [...new Set(question.answer_indices.map(Number))]
    : [];
  const options = Array.isArray(question?.options) ? question.options : [];
  const optionByIndex = new Map(options.map((option) => [Number(option.index), option]));
  const answerOptions = answerIndices.map((index) => optionByIndex.get(index)).filter(Boolean);
  const missingOptions = answerIndices.filter((index) => !optionByIndex.has(index));
  const missingBoxes = answerIndices.filter((index) => {
    const box = optionByIndex.get(index)?.box;
    return !Array.isArray(box) || box.length < 4 || box.some((value) => !Number.isFinite(Number(value)));
  });
  return {
    questionType: question?.question_type,
    answerIndices,
    answerOptions,
    missingOptions,
    missingBoxes,
    confirmBox: question?.confirm_box || null,
    tapConfirm: true,
  };
}

export function frameFingerprint(frame) {
  const value = String(frame || '');
  if (!value) return '';
  const offsets = [0, 0.19, 0.41, 0.63, 0.82, 0.97].map((ratio) => Math.floor(value.length * ratio));
  return `${value.length}:${offsets.map((offset) => value.slice(offset, offset + 24)).join('|')}`;
}

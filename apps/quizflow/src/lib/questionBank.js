const CJK_OR_WORD = /[\p{Script=Han}\p{L}\p{N}]/u;
const ANCHOR_LENGTH = 6;
const MAX_ANCHORS = 3;
const MIN_KEY_PHRASE_COVERAGE = 2 / 3;
const MIN_SIMILARITY = 0.46;
const AMBIGUITY_SCORE_GAP = 0.22;

export function normalizeQuestionText(value = '') {
  return String(value ?? '')
    .normalize('NFKC')
    .toLocaleLowerCase('zh-CN')
    .split('')
    .filter((char) => CJK_OR_WORD.test(char))
    .join('');
}

function orderedAnchorMatch(query, anchors) {
  if (!anchors.length) return false;
  let cursor = 0;
  return anchors.every((anchor) => {
    const position = query.indexOf(anchor, cursor);
    if (position < 0) return false;
    // Allow adjacent key phrases to overlap by one or more characters.
    cursor = position + 1;
    return true;
  });
}

function lcsLength(left, right) {
  const previous = new Array(right.length + 1).fill(0);
  for (let i = 1; i <= left.length; i += 1) {
    let diagonal = 0;
    for (let j = 1; j <= right.length; j += 1) {
      const saved = previous[j];
      previous[j] = left[i - 1] === right[j - 1]
        ? diagonal + 1
        : Math.max(previous[j], previous[j - 1]);
      diagonal = saved;
    }
  }
  return previous[right.length];
}

function similarity(left, right) {
  if (!left || !right) return 0;
  return lcsLength(left, right) / Math.max(left.length, right.length);
}

function grams(value) {
  if (value.length <= ANCHOR_LENGTH) return [value];
  const result = [];
  for (let i = 0; i <= value.length - ANCHOR_LENGTH; i += 1) {
    result.push(value.slice(i, i + ANCHOR_LENGTH));
  }
  return [...new Set(result)];
}

function selectAnchors(normalized, documentFrequency) {
  const candidates = grams(normalized)
    .filter((gram) => gram.length >= ANCHOR_LENGTH)
    .map((gram) => ({ gram, position: normalized.indexOf(gram) }));
  if (candidates.length <= MAX_ANCHORS) return candidates.map(({ gram }) => gram);

  const lastPosition = Math.max(0, normalized.length - ANCHOR_LENGTH);
  const buckets = [0, 0.5, 1].map((ratio) => Math.round(lastPosition * ratio));
  return buckets.map((targetPosition) => candidates
    .slice()
    .sort((left, right) => (
      Math.abs(left.position - targetPosition) - Math.abs(right.position - targetPosition)
      || (documentFrequency.get(left.gram) || 0) - (documentFrequency.get(right.gram) || 0)
    ))[0].gram)
    .filter((gram, position, selected) => selected.indexOf(gram) === position);
}

function enrichRecord(record, documentFrequency) {
  const normalized = normalizeQuestionText(record.question);
  return {
    ...record,
    normalized_question: normalized,
    anchors: Array.isArray(record.anchors) && record.anchors.length > 0
      ? record.anchors.map(normalizeQuestionText).filter(Boolean)
      : selectAnchors(normalized, documentFrequency),
  };
}

export function buildQuestionBankIndex(records = []) {
  const source = Array.isArray(records) ? records : [];
  const documentFrequency = new Map();
  source.forEach((record) => {
    const uniqueGrams = new Set(grams(normalizeQuestionText(record.question)));
    uniqueGrams.forEach((gram) => documentFrequency.set(gram, (documentFrequency.get(gram) || 0) + 1));
  });
  const enriched = source
    .filter((record) => normalizeQuestionText(record.question))
    .map((record) => enrichRecord(record, documentFrequency));
  const exact = new Map();
  enriched.forEach((record) => {
    const list = exact.get(record.normalized_question) || [];
    list.push(record);
    exact.set(record.normalized_question, list);
  });
  return { records: enriched, exact };
}

export function matchQuestionStem(index, question) {
  const query = normalizeQuestionText(question);
  if (!query || !index?.records?.length) return { status: 'not_found', record: null, candidates: [] };

  const exactCandidates = index.exact?.get(query) || [];
  if (exactCandidates.length === 1) {
    return { status: 'exact', record: exactCandidates[0], candidates: exactCandidates };
  }
  if (exactCandidates.length > 1) {
    return { status: 'ambiguous', record: null, candidates: exactCandidates };
  }

  const ranked = index.records
    .map((record) => {
      const matchedAnchors = record.anchors.filter((anchor) => query.includes(anchor));
      const coverage = record.anchors.length ? matchedAnchors.length / record.anchors.length : 0;
      const ordered = coverage >= MIN_KEY_PHRASE_COVERAGE && orderedAnchorMatch(query, matchedAnchors);
      const textSimilarity = similarity(record.normalized_question, query);
      const score = coverage * 0.7 + textSimilarity * 0.3;
      return { record, matchedAnchors, coverage, ordered, score };
    })
    .filter((candidate) => candidate.ordered && candidate.coverage >= MIN_KEY_PHRASE_COVERAGE && candidate.score >= MIN_SIMILARITY)
    .sort((left, right) => right.score - left.score);

  if (!ranked.length) return { status: 'not_found', record: null, candidates: [] };
  const [best, second] = ranked;
  if (second && second.coverage >= MIN_KEY_PHRASE_COVERAGE && best.score - second.score < AMBIGUITY_SCORE_GAP) {
    return { status: 'ambiguous', record: null, candidates: ranked.slice(0, 5) };
  }
  return { status: 'key_phrase', record: best.record, score: best.score, candidates: ranked.slice(0, 5) };
}

export function parseQuestionStemResult(raw) {
  let value = raw;
  if (typeof value === 'string') {
    const text = value.trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '').trim();
    try {
      value = JSON.parse(text);
    } catch (error) {
      throw new Error('题干识别响应不是有效 JSON');
    }
  }
  const question = String(value?.question || '').trim();
  if (!question) throw new Error('题干识别响应缺少 question');
  return { question };
}

export function questionBankMatchToResult(match, recognizedQuestion, questionNo) {
  const record = match?.record || null;
  const statusLabels = {
    exact: '精确匹配',
    key_phrase: '关键短句匹配',
    ambiguous: '匹配不唯一',
    conflict: '题库答案冲突',
    not_found: '题库未命中',
  };
  return {
    question_no: questionNo,
    question_type: record?.type || 'single',
    question: record?.question || recognizedQuestion,
    options: record?.options || [],
    answer_indices: record?.answer_indices || [],
    answer_text: record?.answer_text || '',
    confirm_box: null,
    next_box: null,
    confidence: record ? Math.min(Math.max(Number(match.score) || 0, 0), 1) : 0,
    match_status: match?.status || 'not_found',
    match_label: statusLabels[match?.status] || statusLabels.not_found,
    match_source: record?.sources?.[0]
      ? `${record.sources[0].file || ''} / ${record.sources[0].sheet || ''} / ${record.sources[0].row || ''}`.replace(/\s*\/\s*$/g, '')
      : '题库匹配',
    next_action: 'show_answer_only',
  };
}

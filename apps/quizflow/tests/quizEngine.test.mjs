import test from 'node:test';
import assert from 'node:assert/strict';
import {
  advanceRun,
  createRunState,
  getQuestionAction,
  getActionPlan,
  frameFingerprint,
  parseModelResult,
} from '../src/lib/quizEngine.js';

test('creates a fresh run at question one', () => {
  assert.deepEqual(createRunState({ mode: 'auto' }), {
    mode: 'auto',
    questionNo: 1,
    status: 'idle',
    answered: 0,
  });
});

test('maps question types to their execution action', () => {
  assert.equal(getQuestionAction('single'), 'tap_answer_then_confirm');
  assert.equal(getQuestionAction('true_false'), 'tap_answer_then_confirm');
  assert.equal(getQuestionAction('multiple'), 'tap_answers_then_confirm');
  assert.equal(getQuestionAction('indefinite'), 'tap_answers_then_confirm');
});

test('advances an active run beyond question one hundred without stopping', () => {
  const state = {
    ...createRunState({ mode: 'auto' }),
    status: 'active',
    questionNo: 100,
    answered: 99,
  };

  const q101 = advanceRun(state, 'single');
  assert.equal(q101.questionNo, 101);
  assert.equal(q101.status, 'active');
  assert.equal(q101.answered, 100);

  const q102 = advanceRun(q101, 'multiple');
  assert.equal(q102.questionNo, 102);
  assert.equal(q102.status, 'active');
  assert.equal(q102.answered, 101);
});

test('parses fenced model JSON and validates answer indices', () => {
  const result = parseModelResult(`\n\`\`\`json\n${JSON.stringify({
    question_no: 4,
    question_type: 'multiple',
    question: '选择正确项',
    options: [{ index: 0, text: 'A' }, { index: 1, text: 'B' }],
    answer_indices: [0, 1],
    confirm_box: [20, 88, 60, 8],
    confidence: 0.91,
    next_action: 'tap_answers_then_confirm',
  })}\n\`\`\``);

  assert.equal(result.question_no, 4);
  assert.deepEqual(result.answer_indices, [0, 1]);
  assert.deepEqual(result.confirm_box, [20, 88, 60, 8]);
  assert.equal(result.confidence, 0.91);
});

test('rejects malformed model JSON with a useful error', () => {
  assert.throws(
    () => parseModelResult(JSON.stringify({
      question_type: 'single',
      options: [{ index: 0, text: 'A' }],
      answer_indices: 'one',
    })),
    /answer_indices/,
  );
});

test('keeps option lookup keyed by the model option index and validates tappable boxes', () => {
  const plan = getActionPlan({
    question_type: 'multiple',
    options: [
      { index: 3, text: 'C', box: [10, 20, 30, 8] },
      { index: 7, text: 'G', box: [10, 31, 30, 8] },
    ],
    answer_indices: [7, 3],
    confirm_box: [20, 88, 60, 8],
  });

  assert.deepEqual(plan.answerOptions.map((option) => option.index), [7, 3]);
  assert.equal(plan.tapConfirm, true);
  assert.deepEqual(plan.confirmBox, [20, 88, 60, 8]);
  assert.deepEqual(plan.missingBoxes, []);
});

test('rejects invalid answer cardinality and malformed coordinate boxes', () => {
  assert.throws(
    () => parseModelResult({
      question_type: 'single',
      options: [{ index: 0, text: 'A' }, { index: 1, text: 'B' }],
      answer_indices: [0, 1],
    }),
    /single.*一个|answer_indices/i,
  );

  assert.throws(
    () => parseModelResult({
      question_type: 'true_false',
      options: [{ index: 0, text: '正确', box: [0, 0, 110, 8] }],
      answer_indices: [0],
    }),
    /box|坐标/i,
  );
});

test('accepts an empty answer for a question-bank miss and supports fill-in answers', () => {
  const miss = parseModelResult({
    question_type: 'single',
    question: '未匹配题干',
    options: [],
    answer_indices: [],
    match_status: 'not_found',
    match_label: '题库未命中',
    confidence: 0,
  });
  assert.deepEqual(miss.answer_indices, []);
  assert.equal(miss.match_status, 'not_found');

  const fill = parseModelResult({
    question_type: 'fill_blank',
    question: '填空题',
    options: [],
    answer_indices: [],
    answer_text: '标准答案',
    confidence: 1,
  });
  assert.equal(fill.answer_text, '标准答案');
  assert.equal(fill.question_type, 'fill_blank');
});

test('accepts multiple answers for an indefinite-choice question', () => {
  const result = parseModelResult({
    question_type: 'indefinite',
    question: '不定项选择题',
    options: [{ index: 0, text: 'A' }, { index: 1, text: 'B' }],
    answer_indices: [0, 1],
    confidence: 0.9,
  });
  assert.equal(result.question_type, 'indefinite');
  assert.deepEqual(result.answer_indices, [0, 1]);
});

test('fingerprints frames without retaining the full screenshot', () => {
  const first = `data:image/jpeg;base64,${'A'.repeat(200)}END1`;
  const second = `data:image/jpeg;base64,${'A'.repeat(200)}END2`;
  assert.notEqual(frameFingerprint(first), frameFingerprint(second));
  assert.equal(frameFingerprint(''), '');
});

test('does not advance a run unless it is active', () => {
  for (const status of ['idle', 'paused', 'stopped']) {
    const state = { ...createRunState({ mode: 'auto' }), status };
    assert.deepEqual(advanceRun(state, 'single'), state);
  }
});

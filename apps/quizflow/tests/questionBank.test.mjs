import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {
  buildQuestionBankIndex,
  matchQuestionStem,
  parseQuestionStemResult,
  questionBankMatchToResult,
} from '../src/lib/questionBank.js';

const bank = [
  {
    id: 'q-1',
    question: '智能客服占比的目标值是多少？',
    answer: 'B',
    answer_indices: [1],
    options: [{ index: 0, text: '86.4%' }, { index: 1, text: '88%' }],
  },
  {
    id: 'q-2',
    question: '通过智能化技术提升问题识别和解决能力，减少误判与遗漏。',
    answer: 'D',
    answer_indices: [3],
    options: [{ index: 3, text: '智能化技术' }],
  },
];

test('matches normalized full question text exactly', () => {
  const index = buildQuestionBankIndex(bank);
  const result = matchQuestionStem(index, '智能客服占比的目标值是 多少？');

  assert.equal(result.status, 'exact');
  assert.equal(result.record.id, 'q-1');
});

test('matches OCR-like question text by ordered key phrases', () => {
  const index = buildQuestionBankIndex(bank);
  const result = matchQuestionStem(index, '智能化技术提升问题识别和解决能力减少误判与遗漏');

  assert.equal(result.status, 'key_phrase');
  assert.equal(result.record.id, 'q-2');
});

test('reports an ambiguous fuzzy match instead of selecting a close candidate', () => {
  const index = buildQuestionBankIndex([
    { id: 'a', question: '智能客服业务目标包括哪些内容？', answer: 'A', answer_indices: [0], options: [] },
    { id: 'b', question: '智能客服业务目标包括哪些重点内容？', answer: 'B', answer_indices: [1], options: [] },
  ]);
  const result = matchQuestionStem(index, '智能客服业务目标包括哪些');

  assert.equal(result.status, 'ambiguous');
  assert.equal(result.record, null);
});

test('reports a miss when no question reaches the key phrase threshold', () => {
  const index = buildQuestionBankIndex(bank);
  const result = matchQuestionStem(index, '完全不同的题干内容');

  assert.equal(result.status, 'not_found');
  assert.equal(result.record, null);
});

test('parses the independent question-stem model contract', () => {
  assert.deepEqual(
    parseQuestionStemResult('```json\n{"question":"识别出的题干"}\n```'),
    { question: '识别出的题干' },
  );
  assert.throws(() => parseQuestionStemResult('{"answer_indices":[0]}'), /question/);
});

test('builds a matchable index from an imported bank payload', () => {
  const imported = {
    stats: { workbooks: 1, records: bank.length, matchable_questions: bank.length },
    questions: bank,
  };
  const index = buildQuestionBankIndex(imported.questions);
  const result = matchQuestionStem(index, '智能客服占比的目标值是多少？');
  assert.equal(result.status, 'exact');
  assert.deepEqual(result.record.answer_indices, [1]);
});

test('converts a local match into the existing answer display contract', () => {
  const index = buildQuestionBankIndex(bank);
  const match = matchQuestionStem(index, '智能客服占比目标值是多少');
  const result = questionBankMatchToResult(match, '智能客服占比目标值是多少', 3);

  assert.equal(result.question_no, 3);
  assert.deepEqual(result.answer_indices, [1]);
  assert.equal(result.next_action, 'show_answer_only');
  assert.equal(result.match_status, 'key_phrase');
});

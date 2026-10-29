const questionTemplates = [
  {
    type: 'single',
    question: '在开始一次自动刷题运行前，最适合先确认哪项设置？',
    options: ['当前题目区域清晰可见', '关闭手机网络', '把题目缩小到最小', '清空系统通知'],
    answer_indices: [0],
    confidence: 0.98,
  },
  {
    type: 'multiple',
    question: '以下哪些状态适合写入运行日志？',
    options: ['当前题号', '识别耗时', '完整 API Key', '模型置信度'],
    answer_indices: [0, 1, 3],
    confidence: 0.97,
  },
  {
    type: 'true_false',
    question: '所有题型完成答案选择后，都需要点击确认才能进入下一页。',
    options: ['正确', '错误'],
    answer_indices: [0],
    confidence: 0.99,
  },
  {
    type: 'single',
    question: '模型返回结构化答案后，执行点击前最重要的步骤是什么？',
    options: ['校验 JSON 和选项编号', '立刻连续点击十次', '刷新整个浏览器', '删除运行记录'],
    answer_indices: [0],
    confidence: 0.96,
  },
  {
    type: 'multiple',
    question: '手动模式下，助手可以提供哪些信息？',
    options: ['题型', '推荐选项', '置信度', '代替用户提交最终答卷'],
    answer_indices: [0, 1, 2],
    confidence: 0.95,
  },
];

export function getMockQuestion(questionNo = 1) {
  const safeNo = Math.max(1, Number(questionNo) || 1);
  const template = questionTemplates[(safeNo - 1) % questionTemplates.length];
  return {
    question_no: safeNo,
    question_type: template.type,
    question: template.question,
    options: template.options.map((text, index) => ({
      index,
      text,
      box: [8, 43 + index * 11, 84, 8],
    })),
    answer_indices: [...template.answer_indices],
    confirm_box: [20, 88, 60, 8],
    confidence: template.confidence,
    next_action: template.type === 'multiple' ? 'tap_answers_then_confirm' : 'tap_answer_then_confirm',
  };
}

export function getQuestionTypeLabel(type) {
  return {
    single: '单选题',
    multiple: '多选题',
    indefinite: '不定项选择题',
    true_false: '判断题',
    fill_blank: '填空题',
  }[type] || '待识别';
}

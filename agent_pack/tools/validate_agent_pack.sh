#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

missing_files=()
missing_rules=()

require_file() {
  local rel_path="$1"
  if [[ ! -f "${REPO_ROOT}/${rel_path}" ]]; then
    missing_files+=("${rel_path}")
  fi
}

has_any_match() {
  local pattern="$1"
  shift
  local paths=("$@")

  if grep -R -i -E -q "${pattern}" "${paths[@]}" 2>/dev/null; then
    return 0
  fi
  return 1
}

require_rule_anywhere() {
  local label="$1"
  local pattern="$2"
  shift 2
  local paths=("$@")

  if ! has_any_match "${pattern}" "${paths[@]}"; then
    missing_rules+=("${label}")
  fi
}

require_two_markers_anywhere() {
  local label="$1"
  local pattern_a="$2"
  local pattern_b="$3"
  shift 3
  local paths=("$@")

  if ! has_any_match "${pattern_a}" "${paths[@]}"; then
    missing_rules+=("${label} (missing marker A)")
    return
  fi
  if ! has_any_match "${pattern_b}" "${paths[@]}"; then
    missing_rules+=("${label} (missing marker B)")
  fi
}

require_markers_in_same_file() {
  local label="$1"
  local marker_a="$2"
  local marker_b="$3"
  shift 3
  local paths=("$@")

  local found="false"
  local file
  while IFS= read -r file; do
    if grep -i -q -E "${marker_b}" "${file}" 2>/dev/null; then
      found="true"
      break
    fi
  done < <(grep -R -i -l -E "${marker_a}" "${paths[@]}" 2>/dev/null || true)

  if [[ "${found}" != "true" ]]; then
    missing_rules+=("${label}")
  fi
}

required_files=(
  "CODEX_START.md"
  "agent_pack/AGENTS.md"
  "agent_pack/PROJECT_MEMORY.md"
  "agent_pack/RUNBOOK.md"
  "agent_pack/AUTOPILOT_POLICY.md"
  "agent_pack/ROADMAP.md"
  "agent_pack/BACKLOG.md"
  "agent_pack/prompts/CODEX_MASTER_PROMPT.md"
  "agent_pack/prompts/TASK_FEATURE.md"
  "agent_pack/prompts/TASK_BUGFIX.md"
  "agent_pack/prompts/TASK_DOC_PATCH.md"
  "agent_pack/prompts/TASK_PROMPT_PATCH.md"
  "agent_pack/schema/openapi.yaml"
  "agent_pack/episodes/.gitkeep"
  ".github/workflows/autopilot.yml"
  "scripts/deploy_staging.sh"
  "scripts/smoke_test.sh"
)

for path in "${required_files[@]}"; do
  require_file "${path}"
done

search_paths=(
  "${REPO_ROOT}/CODEX_START.md"
  "${REPO_ROOT}/agent_pack/AGENTS.md"
  "${REPO_ROOT}/agent_pack/PROJECT_MEMORY.md"
  "${REPO_ROOT}/agent_pack/RUNBOOK.md"
  "${REPO_ROOT}/agent_pack/prompts/CODEX_MASTER_PROMPT.md"
  "${REPO_ROOT}/agent_pack/prompts/TASK_FEATURE.md"
  "${REPO_ROOT}/agent_pack/prompts/TASK_BUGFIX.md"
  "${REPO_ROOT}/agent_pack/prompts/TASK_DOC_PATCH.md"
  "${REPO_ROOT}/agent_pack/prompts/TASK_PROMPT_PATCH.md"
  "${REPO_ROOT}/agent_pack/schema/openapi.yaml"
)

# Rule markers (allow minor phrasing/case differences, but require keyword hits)
require_two_markers_anywhere \
  "Project memory is the source of truth" \
  "PROJECT_MEMORY" \
  "(single[[:space:]]+source[[:space:]]+of[[:space:]]+truth|source[[:space:]]+of[[:space:]]+truth|唯一事实源)" \
  "${search_paths[@]}"

require_rule_anywhere \
  "DoD / Definition of Done" \
  "(DoD|Definition[[:space:]]+of[[:space:]]+Done)" \
  "${search_paths[@]}"

require_rule_anywhere \
  "MEMORY_PATCH" \
  "MEMORY_PATCH" \
  "${search_paths[@]}"

require_rule_anywhere \
  "STATE_REPORT" \
  "STATE_REPORT" \
  "${search_paths[@]}"

require_rule_anywhere \
  "PROMPT_REVIEW" \
  "PROMPT_REVIEW" \
  "${search_paths[@]}"

require_rule_anywhere \
  "PROMPT_PATCH" \
  "PROMPT_PATCH" \
  "${search_paths[@]}"

require_markers_in_same_file \
  "episodes retention (episodes + 30)" \
  "episodes" \
  "30" \
  "${search_paths[@]}"

require_markers_in_same_file \
  "contract lock (openapi + contract lock/契约锁)" \
  "openapi" \
  "(contract[[:space:]]+lock|契约锁)" \
  "${search_paths[@]}"

if (( ${#missing_files[@]} > 0 || ${#missing_rules[@]} > 0 )); then
  echo "AgentPack validation: FAIL"
  if (( ${#missing_files[@]} > 0 )); then
    echo ""
    echo "Missing required files:"
    for item in "${missing_files[@]}"; do
      echo "  - ${item}"
    done
  fi
  if (( ${#missing_rules[@]} > 0 )); then
    echo ""
    echo "Missing required rule markers:"
    for item in "${missing_rules[@]}"; do
      echo "  - ${item}"
    done
  fi
  exit 1
fi

echo "AgentPack validation: OK"
exit 0

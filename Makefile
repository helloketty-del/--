.PHONY: help validate dev build test lint

help:
	@echo "AgentPack repo: unified entrypoints (placeholder)"
	@echo ""
	@echo "Targets:"
	@echo "  make validate - AgentPack self-check gate"
	@echo "  make dev   - TODO: define real dev command"
	@echo "  make build - TODO: define real build command"
	@echo "  make test  - TODO: define real test command"
	@echo "  make lint  - TODO: define real lint command"
	@echo ""
	@echo "Source of truth: agent_pack/PROJECT_MEMORY.md -> Engineering & Run -> Commands"

validate:
	@bash agent_pack/tools/validate_agent_pack.sh

dev:
	@echo "TODO: define dev command in agent_pack/PROJECT_MEMORY.md -> Engineering & Run -> Commands"

build:
	@echo "TODO: define build command in agent_pack/PROJECT_MEMORY.md -> Engineering & Run -> Commands"

test: validate
	@echo "TODO: define test command in agent_pack/PROJECT_MEMORY.md -> Engineering & Run -> Commands (currently: validate gate only)"

lint: validate
	@echo "TODO: define lint command in agent_pack/PROJECT_MEMORY.md -> Engineering & Run -> Commands (currently: validate gate only)"

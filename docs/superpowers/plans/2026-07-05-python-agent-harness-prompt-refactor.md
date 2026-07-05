# Python Agent Harness Prompt Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the Python Agent service into a clearer three-layer structure where `harness` owns common LLM prompt composition, `agents` own concrete digital-human prompts, and business/API code calls agents through stable contracts.

**Architecture:** Keep existing runtime APIs compatible while moving prompt composition into `app/harness/prompt_composer.py`. Agent-specific prompt templates move from `app/prompts/` into `app/agents/prompts/<agent_key>/`, while common safety/output fragments live under `app/harness/prompts/`. Existing `app/prompts.py` remains as a compatibility import shim during migration. Business API composition contracts begin moving under `app/business/api/`, with old `app/api/` imports kept as shims.

**Tech Stack:** Python 3.12, FastAPI, Pydantic, LangGraph, pytest, LiteLLM-compatible structured JSON output.

---

### Task 1: Add Harness Prompt Composer Contract Tests

**Files:**
- Create: `python-agent-service/tests/harness/test_prompt_composer.py`

- [ ] **Step 1: Write failing tests**

```python
from pathlib import Path

import pytest

from app.harness.prompt_composer import PromptRepository


def test_prompt_repository_loads_common_fragments_and_agent_prompt() -> None:
    repo = PromptRepository()

    system_prompt, user_prompt = repo.render(
        "intake_analyze",
        {"raw_text": "物流显示签收但用户未收到"},
        {"type": "object"},
    )

    assert "Common AI Native harness safety boundary" in system_prompt
    assert "neutral Dispute Intake Officer" in system_prompt
    assert "<untrusted_case_data>" in user_prompt
    assert "物流显示签收但用户未收到" in user_prompt
    assert "<required_output_schema>" in user_prompt


def test_prompt_repository_resolves_agent_owned_template_path() -> None:
    repo = PromptRepository()

    path = repo.template_path("intake_analyze")

    assert path == Path("app/agents/prompts/dispute_intake_officer/intake_analyze.md")


def test_prompt_repository_rejects_unknown_node() -> None:
    repo = PromptRepository()

    with pytest.raises(KeyError):
        repo.render("unknown_node", {}, {"type": "object"})
```

- [ ] **Step 2: Run red test**

Run: `D:\miniconda\python.exe -m pytest python-agent-service\tests\harness\test_prompt_composer.py -q`

Expected: fail because `app.harness.prompt_composer` does not exist yet.

### Task 2: Move Prompt Templates to Agent-Owned Directories

**Files:**
- Move: `python-agent-service/app/prompts/*.md`
- Create directories under: `python-agent-service/app/agents/prompts/`
- Create: `python-agent-service/app/harness/prompts/safety_boundary.md`
- Create: `python-agent-service/app/harness/prompts/json_output_rules.md`

- [ ] **Step 1: Create prompt directories**

Create these directories:

```text
python-agent-service/app/agents/prompts/dispute_intake_officer/
python-agent-service/app/agents/prompts/evaluation_agent/
python-agent-service/app/agents/prompts/presiding_judge/
python-agent-service/app/agents/prompts/deliberation_panel/
python-agent-service/app/agents/prompts/review_copilot/
python-agent-service/app/harness/prompts/
```

- [ ] **Step 2: Move prompt files**

Move files with this mapping:

```text
intake_analyze.md -> agents/prompts/dispute_intake_officer/intake_analyze.md
evaluation_analyze.md -> agents/prompts/evaluation_agent/evaluation_analyze.md
issue_framing_node.md -> agents/prompts/presiding_judge/issue_framing_node.md
evidence_gap_request_node.md -> agents/prompts/presiding_judge/evidence_gap_request_node.md
party_liaison_node.md -> agents/prompts/presiding_judge/party_liaison_node.md
evidence_cross_check_node.md -> agents/prompts/presiding_judge/evidence_cross_check_node.md
rule_application_node.md -> agents/prompts/presiding_judge/rule_application_node.md
adjudication_draft_node.md -> agents/prompts/presiding_judge/adjudication_draft_node.md
evidence_critic.md -> agents/prompts/deliberation_panel/evidence_critic.md
rule_critic.md -> agents/prompts/deliberation_panel/rule_critic.md
risk_critic.md -> agents/prompts/deliberation_panel/risk_critic.md
remedy_critic.md -> agents/prompts/deliberation_panel/remedy_critic.md
fairness_critic.md -> agents/prompts/deliberation_panel/fairness_critic.md
review_copilot.md -> agents/prompts/review_copilot/review_copilot.md
```

### Task 3: Implement Harness Prompt Composer

**Files:**
- Create: `python-agent-service/app/harness/prompt_composer.py`
- Modify: `python-agent-service/app/prompts.py`

- [ ] **Step 1: Implement `PromptRepository` in harness**

The class should:

- own the node-to-agent prompt mapping;
- prepend common harness prompt fragments;
- keep the existing `render(node_name, case_data, output_schema)` API;
- expose `template_path(node_name)` as a repo-relative path for tests and future debugging.

- [ ] **Step 2: Keep compatibility shim**

`app/prompts.py` should re-export:

```python
from app.harness.prompt_composer import PromptComposer, PromptRepository, PromptTemplateRef
```

### Task 4: Update Production Imports

**Files:**
- Modify: `python-agent-service/app/intake.py`
- Modify: `python-agent-service/app/evaluation.py`
- Modify: `python-agent-service/app/graph.py`
- Modify: `python-agent-service/app/workflow.py`
- Modify: `python-agent-service/app/agents/model_roles.py`
- Modify: `python-agent-service/app/main.py`

- [ ] **Step 1: Replace production imports**

Change:

```python
from app.prompts import PromptRepository
```

to:

```python
from app.harness.prompt_composer import PromptRepository
```

Tests may continue importing `app.prompts` to verify backward compatibility.

- [ ] **Step 2: Move API composition contract toward the business layer**

Create `python-agent-service/app/business/api/final_agents.py` with the existing
`FinalAgentServices` protocol/dataclass definitions. Update `app/main.py` to import
from `app.business.api.final_agents`, and keep `app/api/final_agents.py` as a
compatibility shim that re-exports the same names.

### Task 5: Verify and Commit

**Files:**
- All changed Python files
- Existing Java/Python fixes already in the working tree

- [ ] **Step 1: Run Python prompt and Agent tests**

Run:

```powershell
D:\miniconda\python.exe -m pytest python-agent-service\tests\test_prompts.py python-agent-service\tests\harness\test_prompt_composer.py python-agent-service\tests\agents\test_intake_turn.py python-agent-service\tests\harness\test_memeo_memory.py -q
```

- [ ] **Step 2: Run Java regression tests covering existing uncommitted fixes**

Run:

```powershell
cd java-api-service
.\mvnw.cmd -Dtest=CaseApplicationServiceTest,IntakeAgentTurnServiceTest,DisputeControllerTest test
```

- [ ] **Step 3: Commit and push**

Run:

```powershell
git add python-agent-service java-api-service docs/superpowers/plans/2026-07-05-python-agent-harness-prompt-refactor.md
git commit -m "refactor: centralize agent prompt composition in harness"
git push origin main
```

# Engineering Harness State

This file contains the persistent working state for the AI Engineering Harness.

It is intended to allow a new AI agent or model to understand the current engineering situation without relying on previous conversational context.

Keep this file concise, current, and evidence-based.

---

# Harness Status

```text
Status: INITIALIZATION
Lifecycle Stage: HARNESS DESIGN
Current Project: NONE
```

---

# Current Objective

Establish the minimum viable structure for a persistent, model-independent AI engineering harness capable of directing advanced AI agents through serious software-engineering investigations.

The harness should support projects that may continue across multiple sessions, days, or weeks.

---

# Current Repository Model

The minimum initial control plane consists of:

```text
README.md
AGENTS.md
STATE.md
```

Responsibilities:

```text
README.md
    public mission
    architecture
    engineering philosophy
    project lifecycle

AGENTS.md
    mandatory agent behavior
    evidence requirements
    implementation constraints
    testing rules
    state handoff rules

STATE.md
    current engineering state
    active objective
    confirmed decisions
    unresolved questions
    handoff information
```

Additional files should not be introduced until a real engineering project demonstrates the need for them.

---

# Confirmed Design Decisions

## 1. This is not a daily challenge repository

Projects may remain active for as long as required.

Engineering quality takes precedence over artificial daily completion.

---

## 2. The harness is model-independent

The repository must preserve enough state that work can move between different AI models and tools.

The model is replaceable.

The repository owns persistent engineering memory.

---

## 3. Evidence precedes implementation

The normal defect workflow is:

```text
DISCOVERY
-> REPRODUCTION
-> EVIDENCE
-> ROOT CAUSE
-> DESIGN
-> IMPLEMENTATION
-> VERIFICATION
-> REVIEW
```

Agents should not jump directly from issue description to speculative patch.

---

## 4. Human engineering authority remains explicit

AI agents may perform substantial investigation and implementation.

The human engineer retains authority over:

* objectives
* scope
* risk
* architecture
* acceptance
* external publication
* production access

---

## 5. The harness should demonstrate serious engineering

Target problem classes include:

* concurrency
* memory/resource leaks
* runtime failures
* distributed systems
* pathological performance
* CI/CD
* infrastructure
* database consistency
* networking
* production reliability
* difficult open-source defects

The primary public signal should be engineering judgment and evidence, not generated code volume.

---

# Current Architecture

```text
Human Engineer
      |
      v
AI Engineering Harness
      |
      +--> Investigation
      |
      +--> Systems Analysis
      |
      +--> Implementation
      |
      +--> Testing
      |
      +--> Independent Review
      |
      v
Human Acceptance
```

Agent roles are conceptual.

They may be performed by separate models or separate sessions of the same model.

---

# Current Engineering Principles

1. Reproduce before repairing where reasonably possible.
2. Preserve evidence.
3. Maintain competing hypotheses.
4. Establish failure mechanism before implementation.
5. Prefer minimal repairs.
6. Test the failure contract.
7. Attempt to disprove the fix.
8. Record important architectural decisions.
9. Maintain least-privilege access.
10. Preserve cross-model handoff state in Git.

---

# Active Project

None.

The next substantial step should be to select the first real engineering problem for the harness.

The first project should ideally be difficult enough to require:

* repository understanding
* diagnosis
* experimentation
* root-cause reasoning
* implementation
* regression testing
* adversarial verification

Avoid trivial CRUD applications or simple feature generation as the first flagship demonstration.

---

# Candidate First-Project Characteristics

Prefer a problem with several of the following:

* real failure report
* non-obvious root cause
* existing substantial codebase
* observable failure
* concurrency or lifecycle complexity
* measurable performance behavior
* meaningful regression risk
* possibility of an upstream contribution
* strong verification path

Possible domains:

```text
JVM / Java
Netty
Gradle
Elasticsearch
Kubernetes
CI runners
distributed systems
database engines
runtime infrastructure
```

---

# Open Questions

* Which real engineering problem should become the first flagship harness project?
* Should individual projects live directly in this repository or use external repositories with this harness acting as the control plane?
* At what point should project-specific files such as `HYPOTHESES.md` and `EVIDENCE.md` be introduced?
* How should multiple agents hand work to one another once orchestration becomes automated?
* What verification threshold should be required before publishing a project as completed?
* How should agent execution history be captured without turning Git into a conversational transcript archive?

---

# Rejected Approaches

## Daily completion requirement

Rejected.

Reason:

Complex engineering problems should remain active until sufficiently understood and verified.

---

## AI-generated application showcase

Rejected as the primary purpose.

Reason:

The repository should demonstrate control of advanced AI engineering workflows rather than the ability to generate ordinary applications.

---

## Model-specific persistent memory

Rejected.

Reason:

Long-term engineering state must survive model replacement.

---

# Next Recommended Action

Select one difficult real-world engineering problem and initialize it as the first harness project.

Before implementation begins, the agent should establish:

```text
problem statement
repository / system under investigation
expected behavior
observed behavior
environment
initial evidence
reproduction plan
```

The first project should be treated as a test of the harness itself.

Any weaknesses discovered in the engineering process should result in improvements to the harness rules rather than ad-hoc conversational instructions.

---

# Handoff

A new agent entering this repository should:

1. Read `README.md`.
2. Read `AGENTS.md`.
3. Read this file.
4. Confirm that no active engineering project currently exists.
5. Help select or initialize the first serious investigation.
6. Do not begin speculative implementation before defining and reproducing the engineering problem.

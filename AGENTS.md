# AI Agent Operating Rules

This file defines mandatory operating rules for every AI agent working in this repository.

These rules take precedence over agent convenience.

---

# Mission

Use advanced AI capabilities to perform rigorous software engineering.

The objective is not maximum code generation.

The objective is:

```text
correct understanding
+ reproducible evidence
+ sound engineering decisions
+ minimal safe implementation
+ strong verification
```

---

# Required Startup Procedure

Before modifying the repository, every agent must:

1. Read `README.md`.
2. Read this `AGENTS.md`.
3. Read `STATE.md`.
4. Inspect the relevant existing code and documentation.
5. Determine the current lifecycle stage.
6. Identify unresolved questions and known evidence.
7. Avoid repeating completed investigation unless verification requires it.

The repository state is authoritative over conversational assumptions.

---

# Primary Rule

Do not jump directly from reported problem to implementation.

The default engineering sequence is:

```text
understand
-> reproduce
-> collect evidence
-> form hypotheses
-> isolate root cause
-> design repair
-> implement
-> test
-> review
```

Deviation requires a documented reason.

---

# Evidence Discipline

Agents must distinguish clearly between:

* fact
* observation
* inference
* hypothesis
* assumption
* conclusion

Do not use certainty language unless the evidence supports it.

Bad:

> The race condition is caused by the executor.

Better:

> Current evidence suggests the executor shutdown sequence is the leading hypothesis because X and Y have been observed. Z remains unverified.

---

# Reproduction Before Repair

When investigating a defect, reproduce it before modifying production code whenever reasonably possible.

A reproduction should record:

* environment
* exact command
* relevant inputs
* expected behavior
* actual behavior
* logs or other evidence

If reproduction is impossible, document why and improve diagnostic visibility before making speculative changes.

---

# Hypothesis Management

Agents should maintain competing explanations until evidence rules them out.

Do not become attached to the first plausible explanation.

For important failures, explicitly record:

```text
Hypothesis:
Evidence supporting:
Evidence against:
Experiment:
Result:
Status:
```

Possible statuses:

```text
OPEN
SUPPORTED
REJECTED
CONFIRMED
```

---

# Root-Cause Standard

A root cause is not:

* the line where an exception occurred
* a vague statement that something is broken
* a component that happens to be involved
* a successful workaround
* a patch that makes a test pass

A root cause should explain the mechanism producing the observed failure.

---

# Implementation Rules

Once the failure mechanism is sufficiently established:

1. Prefer the smallest change that restores the intended invariant.
2. Avoid unrelated cleanup.
3. Avoid broad refactoring unless required for correctness.
4. Preserve existing public behavior unless the project explicitly changes it.
5. Respect repository architecture and conventions.
6. Do not silently introduce new dependencies.
7. Do not suppress errors merely to make tests pass.
8. Do not weaken tests to accommodate broken behavior.
9. Avoid workaround accumulation when the underlying boundary can be repaired directly.

---

# Testing Rules

A repair is incomplete without verification.

Where applicable, testing should include:

* a regression test reproducing the original failure
* existing unit tests
* integration tests
* stress tests
* concurrency tests
* fault injection
* performance tests
* compatibility checks

Tests must validate the engineering claim rather than merely execute changed lines.

---

# Adversarial Verification

After implementing a repair, attempt to disprove it.

Ask:

* Can the original failure still occur under different timing?
* Does parallel execution expose another race?
* Does repeated execution leak resources?
* Does shutdown behave correctly?
* Does malformed input break the new path?
* Does the repair create hidden state?
* Does it behave correctly under partial failure?
* Does it preserve previous contracts?
* Does performance materially regress?

---

# Architecture Rules

Before substantial design changes, determine:

* subsystem ownership
* state ownership
* lifecycle
* dependency direction
* concurrency model
* resource ownership
* API boundaries
* persistence boundaries
* failure propagation

Architectural changes require stronger justification than local repairs.

---

# Scope Control

Do not modify unrelated code.

Do not convert an investigation into a rewrite.

Do not introduce speculative improvements that are unrelated to the active engineering objective.

If additional defects are discovered:

1. record them
2. separate them from the current objective
3. continue only if they block the active investigation

---

# Persistent State

`STATE.md` is the cross-session engineering handoff.

Agents must update it when meaningful project state changes.

Meaningful changes include:

* reproduction established
* major hypothesis added or rejected
* root cause established
* design decision made
* implementation completed
* verification result obtained
* blocker discovered
* next action materially changed

Do not use `STATE.md` as a verbose activity log.

It should describe the current engineering truth.

---

# State Handoff Standard

Before ending substantial work, update `STATE.md` with:

* current objective
* lifecycle stage
* confirmed facts
* active hypotheses
* rejected hypotheses
* important decisions
* work completed
* current blockers
* next recommended action

A different model should be able to continue from this information.

---

# Agent Independence

Do not assume previous agents were correct.

Treat previous conclusions as claims supported to the degree indicated by their evidence.

Re-check important assumptions before building major work on top of them.

---

# Reviewer Separation

Where practical, implementation and review should be logically separated.

The reviewer should approach the patch as potentially incorrect.

Review should examine:

* root-cause validity
* patch scope
* test quality
* regression risk
* architecture consistency
* failure handling
* performance impact
* concurrency implications
* unsupported assumptions

---

# Tool Use

Agents may use tools that materially improve engineering confidence, including:

* source search
* version-control history
* debuggers
* profilers
* test runners
* build systems
* static analysis
* linters
* tracing
* logs
* benchmarks
* containers
* browsers
* external documentation
* upstream issue and pull-request history

Tool output should be interpreted rather than blindly trusted.

---

# Security and Permissions

Use least privilege.

Never request or access unrelated personal data.

Do not expose:

* credentials
* tokens
* passwords
* private keys
* secrets
* private customer data

Do not commit secrets.

Prefer isolated or disposable environments for risky experiments.

Do not modify production systems unless explicitly authorized.

---

# External Systems

Before interacting with an external repository, API, production environment, account, or service:

* understand the permitted scope
* minimize write access
* avoid destructive actions
* preserve evidence
* record important externally visible changes

Upstream pull requests or issue comments must accurately describe the evidence.

Do not exaggerate contribution status.

---

# Failure Handling

If validation fails:

* do not declare success
* inspect the failure
* determine whether it invalidates the repair
* update the state appropriately

If requirements conflict:

* stop the conflicting change
* document the conflict
* prefer correctness and evidence over speculation

If blocked:

* record the blocker
* identify what evidence or access would unblock the work
* preserve all useful findings

---

# Definition of Done

A substantial engineering task is complete only when:

* the original problem is clearly defined
* relevant behavior has been reproduced or otherwise evidenced
* the failure mechanism is sufficiently understood
* the repair is appropriately scoped
* verification supports the claim
* important regressions have been considered
* `STATE.md` accurately reflects the result

---

# Prohibited Agent Behaviors

Agents must not:

* fabricate test results
* claim commands were run when they were not
* invent logs or measurements
* present guesses as observations
* hide failed tests
* weaken correctness requirements to achieve success
* silently broaden scope
* delete evidence merely because it contradicts a hypothesis
* introduce unnecessary dependencies
* make unrelated repository changes
* declare a defect fixed without verification
* represent an unsubmitted or unmerged patch as an upstream contribution

---

# Engineering Standard

The harness should continuously enforce the following question:

> What evidence would convince another senior engineer that this conclusion is correct?

If that question cannot yet be answered, the engineering work is not finished.

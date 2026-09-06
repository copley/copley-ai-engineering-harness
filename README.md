# AI Engineering Harness

A model-independent engineering harness for directing advanced AI agents to investigate, design, debug, repair, test, verify, and improve real software systems.

This repository is not intended to demonstrate that AI can generate code.

Its purpose is to demonstrate that advanced AI can be directed through a disciplined engineering process capable of solving difficult software-engineering problems with evidence, persistent state, verification, and human oversight.

The long-term objective is to build a reusable engineering control system that becomes more capable as AI models improve.

---

## Purpose

The harness is designed for engineering problems such as:

* concurrency failures
* race conditions
* deadlocks
* memory and resource leaks
* pathological performance
* JVM/runtime failures
* distributed-system bugs
* database consistency failures
* networking problems
* CI/CD instability
* build reproducibility
* infrastructure failures
* Kubernetes/runtime failures
* API and integration failures
* architectural defects
* regression diagnosis
* open-source bug investigation
* production incident reconstruction

Projects may remain active for hours, days, or weeks.

There is no requirement to produce a new project every day.

The objective is to move difficult engineering problems from uncertainty to verified engineering fact.

---

# Core Principle

The harness must never treat:

```text
issue -> patch
```

as an acceptable engineering process.

The intended lifecycle is:

```text
DISCOVERY
    |
    v
REPRODUCTION
    |
    v
EVIDENCE
    |
    v
ROOT CAUSE
    |
    v
DESIGN
    |
    v
IMPLEMENTATION
    |
    v
REGRESSION TESTING
    |
    v
STRESS / PERFORMANCE / INTEGRATION TESTING
    |
    v
REVIEW
    |
    v
VERIFIED RESULT
```

An AI agent saying that something is fixed is not evidence that it is fixed.

The harness must require proof.

---

# Operating Model

The repository acts as the persistent control plane between the human engineer and one or more AI models.

```text
                  Human Engineer
                        |
                        v
              AI Engineering Harness
                        |
          +-------------+-------------+
          |             |             |
          v             v             v
    Investigation   Implementation   Verification
       Agent            Agent           Agent
          |             |             |
          +-------------+-------------+
                        |
                        v
                     Review
                        |
                        v
                 Human Approval
```

The human remains the engineering authority.

AI agents may perform substantial autonomous work, but they operate within explicit constraints and must preserve evidence supporting their conclusions.

---

# Human Role

The human engineer is responsible for:

* defining engineering objectives
* controlling scope
* approving major architectural decisions
* determining acceptable risk
* challenging unsupported conclusions
* deciding what evidence is sufficient
* accepting or rejecting repairs
* approving external publication or upstream contribution
* defining which tools, systems, credentials, and environments agents may access

The harness should amplify engineering judgment rather than remove accountability.

---

# Agent Roles

A project may use one model or multiple models.

Roles are conceptual and may be performed by different agents or by the same model in separate sessions.

## Investigator

Responsible for:

* repository inspection
* source tracing
* reproduction
* instrumentation
* hypothesis generation
* experiment design
* evidence collection

The investigator should avoid premature repair.

---

## Systems Engineer

Responsible for reasoning about:

* lifecycle
* ownership
* state
* concurrency
* resource boundaries
* architecture
* failure propagation
* dependency behavior
* distributed interactions

---

## Implementer

Responsible for producing the smallest defensible change that addresses an established root cause.

Implementation must not begin merely because a possible fix appears obvious.

---

## Test Engineer

Responsible for attempting to disprove the repair.

This may include:

* regression tests
* integration tests
* stress tests
* concurrency tests
* fault injection
* performance tests
* compatibility testing
* adversarial edge cases

---

## Reviewer

Responsible for independently evaluating:

* whether the root cause is supported
* whether the patch is minimal
* whether unrelated behavior changed
* whether architecture constraints were violated
* whether tests actually exercise the failure
* whether important risks remain

---

# Persistent Engineering Memory

The harness must not depend on the conversational memory of a particular AI model.

Git is the authoritative persistent memory.

The minimum control files are:

```text
README.md
AGENTS.md
STATE.md
```

`README.md` defines the purpose and engineering philosophy.

`AGENTS.md` defines mandatory behavior for AI agents.

`STATE.md` records the current engineering state and handoff information.

As the repository grows, individual investigations may introduce additional project-specific artifacts.

---

# Project Structure

A mature repository may evolve toward:

```text
ai-engineering-harness/
├── README.md
├── AGENTS.md
├── STATE.md
└── projects/
    ├── project-a/
    ├── project-b/
    └── project-c/
```

Individual projects may later contain artifacts such as:

```text
PROBLEM.md
HYPOTHESES.md
EVIDENCE.md
DECISIONS.md
ARCHITECTURE.md
TASKS.md
REPRODUCTION.md
ROOT_CAUSE.md
VERIFICATION.md
```

These files should be added only when their value is demonstrated.

The harness should avoid documentation bureaucracy for its own sake.

---

# Project Lifecycle

## 1. Discovery

Understand:

* the repository
* the subsystem
* reported behavior
* environment
* expected behavior
* known constraints
* previous attempts
* related issues or pull requests

No fix should be assumed.

---

## 2. Reproduction

Establish whether the failure can be reproduced.

Record:

* exact commands
* environment
* configuration
* inputs
* output
* logs
* timing
* relevant system state

Prefer deterministic reproduction where possible.

When deterministic reproduction is impossible, improve observability.

---

## 3. Evidence

Collect evidence capable of distinguishing between competing explanations.

Examples include:

* logs
* traces
* thread dumps
* heap evidence
* JFR recordings
* metrics
* profiling
* packet captures
* database state
* source inspection
* call graphs
* event sequences
* minimal reproducers
* before/after measurements

---

## 4. Root Cause

A root-cause statement must explain:

* what failed
* where it failed
* under what conditions
* why the observed symptoms occur
* why competing hypotheses are less likely or rejected

Do not confuse correlation with root cause.

---

## 5. Design

Determine the smallest safe repair.

Consider:

* ownership
* lifecycle
* API contracts
* backwards compatibility
* concurrency semantics
* performance
* failure handling
* operational impact
* maintainability

Important design decisions should be recorded.

---

## 6. Implementation

Implement the repair with minimal unrelated change.

Avoid opportunistic refactoring unless required for correctness.

---

## 7. Verification

Verification must target the original failure contract.

A valid verification strategy normally includes:

* regression test
* successful reproduction before repair
* failed reproduction after repair
* relevant existing test suites
* additional stress or integration testing where appropriate

---

## 8. Review

The repair should be evaluated independently.

Questions include:

* Does the evidence actually prove the diagnosis?
* Does the test fail for the correct reason?
* Could the repair hide rather than solve the problem?
* Are new races or failure modes introduced?
* Are assumptions documented?
* Is the change unnecessarily broad?

---

## 9. Completion

A project is complete only when its engineering claims are supported by reproducible evidence.

Possible outcomes include:

* verified repair
* upstream pull request
* confirmed root cause without repair
* rejected hypothesis
* documented environmental issue
* unreproducible issue with improved diagnostics
* intentionally abandoned investigation with rationale

Failure to find a repair is still useful when the investigation produces defensible engineering knowledge.

---

# Model Independence

The harness must not depend on one AI vendor or model.

Projects should be transferable between:

* OpenAI models
* Anthropic models
* Google models
* local models
* future coding agents
* specialist engineering agents

A replacement agent should be able to read the repository and continue the investigation without reconstructing the entire history from conversation logs.

---

# Evidence Standard

Engineering claims should be classified mentally as:

```text
UNKNOWN
HYPOTHESIS
OBSERVED
REPRODUCED
SUPPORTED
VERIFIED
```

Agents must not present hypotheses as established facts.

---

# Security Principle

AI agents should receive the minimum privileges necessary to complete the task.

Prefer:

* dedicated project directories
* isolated environments
* containers
* disposable test systems
* limited credentials
* least-privilege API access

Avoid unnecessary access to:

* personal files
* unrelated repositories
* browser sessions
* production credentials
* private keys
* secrets
* unrestricted host control

---

# Public Engineering Standard

The repository should demonstrate engineering capability rather than volume of generated code.

Strong projects should show:

```text
ambiguous failure
        |
        v
system understanding
        |
        v
reproduction
        |
        v
evidence
        |
        v
root cause
        |
        v
bounded repair
        |
        v
verification
```

The intended signal is:

> I know how to direct increasingly capable AI systems to perform serious, evidence-driven software engineering.

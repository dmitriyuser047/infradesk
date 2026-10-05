# Mandatory development and Code Review standard

Before planning an implementation, writing or changing code, or performing Code Review,
every agent and developer MUST read the full
[InfraDesk development and Code Review standard](docs/development-and-code-review-standard.md)
and follow its requirements. Reading a summary does not replace reading the document.
This requirement applies to the primary agent and every delegated agent for all code
in this repository. Include the document path and the read-before-work requirement
in each delegation packet.

# Adaptive multi-agent development workflow

The primary agent is the technical lead AND the default implementer.

The goal of multi-agent routing is to reduce total cost and elapsed time
without reducing implementation quality.

Delegation is optional. Do not spawn an agent merely because a task is
non-trivial.


## 1. Primary agent responsibilities

The primary agent must:

1. Understand the user's actual goal.
2. Inspect only the code needed to make the initial routing decision.
3. Make architectural and design decisions itself.
4. Decide whether direct execution or delegation is more efficient.
5. Keep ownership of the overall task.
6. Review delegated changes before accepting them.
7. Handle Git, CI, tags, releases, deployment, and other consequential
   repository operations itself.


## 2. Prefer direct execution

The primary agent SHOULD implement the task itself when:

- the change is small or medium-sized;
- only a few tightly related files are involved;
- implementation is strongly coupled to architectural reasoning;
- the primary agent already has most of the necessary context;
- the implementation is shorter than the delegation handoff;
- explaining the task to a worker would duplicate substantial context;
- debugging is ambiguous and requires repeated reasoning about the same code;
- a worker result would require the primary agent to re-read most of the
  same context.

Do not delegate simply to save model cost when delegation would increase
total tokens or elapsed time.


## 3. Use `scanner` selectively

Use the `scanner` agent only for substantial read-only repository discovery.

Good scanner tasks:

- find implementations of a specific pattern across the repository;
- find call sites or references;
- compare multiple existing implementations;
- identify the likely file set for a change;
- investigate independent questions that can be answered without modifying code.

Do not use scanner when the primary agent can answer the question by opening
a few obvious files.

When spawning scanner:

- use `agent_type="scanner"`;
- use `fork_turns="none"`;
- provide a self-contained search request;
- ask for concise results with exact file paths and symbols;
- do not send the entire parent conversation.


## 4. Use `worker` only for substantial bounded execution

Delegate implementation to `worker` only when ALL of the following are true:

- the architecture is already decided;
- the goal is clear;
- ambiguity is low;
- write scope can be clearly assigned;
- the work is substantial enough to justify a handoff;
- the result can be independently validated;
- the worker does not need to rediscover the whole project.

Good worker tasks include:

- repetitive changes across multiple files;
- implementation of a component whose design is already fixed;
- writing or updating many tests from an established pattern;
- mechanical migrations;
- boilerplate;
- bounded CRUD/API/UI implementation;
- large but straightforward refactors;
- routine fixes after a build or test failure when the cause is already known.

Do NOT delegate:

- tiny edits;
- one-method fixes;
- tightly coupled 1-3 file changes unless implementation is unusually large;
- unresolved architecture;
- ambiguous debugging;
- release operations;
- Git history manipulation;
- CI decisions;
- tag or release creation.


## 5. Delegation packet

Before starting a worker, the primary agent must create a small,
self-contained task packet containing:

- Goal
- Exact write scope
- Files to inspect when known
- Existing implementation/pattern to follow
- Architectural constraints
- Expected behavior
- What must NOT be changed
- Exact validation/build/test command
- Definition of done

Do not send unnecessary project history.

When spawning a worker:

- use `agent_type="worker"`;
- use `fork_turns="none"`;
- rely on the worker role's configured model;
- do not copy the full parent conversation.

The worker must not make new architectural decisions unless the primary agent
explicitly asks for one bounded local decision.


## 6. Parallel execution

Parallel workers are allowed only when their write scopes do not overlap.

Use at most two workers concurrently.

Do not parallelize tasks that modify shared interfaces, shared configuration,
the same files, or tightly coupled behavior.

If ownership is unclear, keep the work serial or execute it directly in the
primary agent.


## 7. Review policy

After a worker completes:

1. Read its summary and validation evidence.
2. Inspect the actual diff.
3. Check architectural constraints and unintended changes.
4. Do not repeat the entire original investigation unless the diff gives a
   concrete reason to do so.

If the change is correct and validation passed, accept it.

If there is a small obvious issue, the primary agent should fix it directly.

Delegate a correction back to the same worker only when the correction is
substantial enough to justify another agent turn.

Avoid repeated worker-review-worker-review loops.


## 8. Failure and escalation

A worker gets at most one substantial correction round.

If the worker cannot complete the bounded task correctly after that:

- stop delegating that piece;
- the primary agent takes ownership and resolves it directly.

Do not create additional agents merely to retry the same failed approach.


## 9. Context-efficiency rules

Context duplication is a cost.

Therefore:

- prefer `fork_turns="none"` for delegated agents;
- send concise self-contained task packets;
- do not ask workers to broadly re-investigate architecture already understood
  by the primary agent;
- do not ask the primary agent to fully re-investigate a worker's solution
  unless validation or diff review reveals a problem;
- reuse an existing worker for a correction rather than spawning a new one
  when practical.


## 10. Completion

The primary agent remains responsible for final completion.

Before reporting success:

- inspect the final diff;
- run or verify the appropriate tests/build;
- confirm the requested behavior;
- perform Git/CI/release operations requested by the user.

Only then provide the final response.

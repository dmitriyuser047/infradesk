# Multi-agent development workflow

The primary agent is the technical lead and primary implementer.

For every coding task, the primary agent MUST:

1. Analyze the task and inspect relevant existing code.
2. Find similar implementations when useful.
3. Determine the correct architectural approach.
4. Decide whether delegation would materially reduce cost, execution time,
   or context usage.

Use the `worker` agent only when the delegated work is substantial and
well-scoped.

Good tasks to delegate include:
- large repetitive changes across many files;
- implementation of a substantial independent component after the
  architecture has already been decided;
- writing or updating many tests following an established pattern;
- mechanical migrations or refactors;
- independent repository investigation that can reduce primary-agent context;
- large well-defined fixes that require little architectural judgment.

Do NOT delegate when:
- the change is small or medium-sized;
- only a few files need modification;
- implementation is tightly coupled to architectural reasoning;
- the primary agent has already loaded most of the necessary context;
- explaining the task to a worker would likely cost more than implementing it;
- the worker result would require substantial re-analysis by the primary agent.

When delegating:

1. Make all important architectural decisions before delegation.
2. Give the worker a precise and self-contained task.
3. Avoid making the worker rediscover context already understood by the
   primary agent.
4. Review the worker's resulting diff before accepting it.
5. Delegate corrections only when doing so is cheaper than fixing them directly.

For ordinary coding tasks, the primary agent should implement the solution
itself.

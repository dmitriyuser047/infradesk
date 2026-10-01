# Multi-agent development workflow

For every non-trivial coding task, the primary agent MUST act as the technical lead.

The primary agent MUST:

1. Analyze the task and inspect relevant existing code.
2. Find similar implementations and determine the correct architectural approach.
3. Make all important design and architecture decisions itself.
4. Once the implementation approach is clear, MUST delegate the implementation
   to the `worker` agent.
5. Give `worker` a precise implementation task including:
   - files to inspect or modify;
   - existing code/patterns to follow;
   - architectural constraints;
   - expected behavior;
   - validation/build/tests to run.
6. Do not delegate unresolved architecture decisions to `worker`.
7. After `worker` finishes, inspect the resulting diff yourself.
8. If the implementation has problems, either fix them or delegate a precise
   correction back to `worker`.
9. Only provide the final response after reviewing the completed implementation.

The primary agent may implement the change itself only for trivial changes
where spawning a worker would clearly add unnecessary overhead.

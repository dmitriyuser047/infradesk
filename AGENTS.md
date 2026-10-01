# Agent workflow

For non-trivial coding tasks, the primary agent acts as the technical lead.

Workflow:

1. The primary agent must first analyze the task and relevant existing code.
2. The primary agent is responsible for architecture, design decisions,
   identifying existing project patterns, and producing the implementation approach.
3. Once the implementation approach is clear, delegate routine implementation
   work to a subagent.
4. Give the subagent a concrete implementation task with:
   - files to inspect or modify;
   - existing implementations to follow;
   - architectural constraints;
   - expected behavior;
   - tests or compilation checks to run.
5. Do not delegate unresolved architectural decisions to the implementation subagent.
6. After the subagent finishes, the primary agent must review the resulting diff.
7. The primary agent must correct or delegate fixes for any issues found.
8. The primary agent gives the final answer only after reviewing the implementation.

Use subagents primarily for implementation, repetitive edits, tests,
compilation fixes, and other well-scoped execution work.

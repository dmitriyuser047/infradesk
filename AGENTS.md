# Mandatory development and Code Review standard

Before planning an implementation, writing or changing code, or performing Code Review,
the agent and every developer MUST read the full
[InfraDesk development and Code Review standard](docs/development-and-code-review-standard.md)
and follow its requirements. Reading a summary does not replace reading the document.
This requirement applies to all code in this repository.

# Single-agent development workflow

Work with one primary agent and the model selected by the user for the session.
The primary agent is the technical lead, implementer and reviewer.

Do not spawn subagents, delegate work, or use worker, scanner, explorer or other
agent roles. Do not route tasks to additional models. Apply this rule to repository
investigation, implementation, tests, Code Review and release work alike.

## Responsibilities

The primary agent must:

1. Understand the user's actual goal and inspect the relevant existing code.
2. Make architectural decisions and implement the work itself.
3. Follow existing InfraDesk patterns and keep changes within the requested scope.
4. Review its own diff for correctness, security, performance and unintended changes.
5. Run the appropriate tests and builds, including PostgreSQL integration tests when
   database behavior is involved.
6. Handle requested Git, CI, tags, releases and deployment operations itself.
7. Keep ownership of the task until the requested work is complete.

## Execution and verification

Use tools directly. Independent read-only searches or checks may be batched without
creating additional agents. Keep dependent edits and operations sequential.

Before reporting success:

- inspect the final diff;
- run or verify the appropriate tests/build;
- confirm the requested behavior;
- verify relevant CI for the exact commit when CI is required;
- complete Git/CI/release operations requested by the user;
- report remaining limitations and distinguish automated validation from live acceptance.

Do not weaken validation, ownership, permissions, tenant isolation, fencing or
fail-closed behavior to make a test pass.

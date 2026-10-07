# Stage 25: partial installation and package probe regressions

The production d1920bd JAR extracted from the VPS contains CRLF in its compiled shell literals. Running its exact RecoveryProbe with `sh -n` fails at the parent-directory `for` loop. The previous adapter folded that nonzero exit into STATE_UNKNOWN. Python extraction fixtures normalized line endings and therefore did not reproduce the compiled artifact failure.

ProfileCommands now normalizes only backend shell script arguments to LF before execution. Ubuntu 24.04 regression executes the compiled production RecoveryProbe through PosixArgv, with owner 700, staging 755, only env 600, no final directory/container and a free port. The layered managed UFW observation preserves OWNED_PARTIAL. The same test executes a CRLF variant through ProfileCommands and covers hash, port and execution failures.

Every UNKNOWN branch in RecoveryProbe names a closed diagnosis. Probe execution errors and invalid output are distinct from unknown remote state. Retirement continues to use the previous owner, node, resource, image and CIDRs. Recovery observation retains its existing correlation; a new mutation correlation is allocated only for an executable immutable plan. CONFIRMED_NOT_FOUND with owned artifacts uses controlled retirement followed by creation, with no DELETE_NODE.

Package observation persists allowlisted package findings (name, optional three-character dpkg state, classification and suggested action) with its observation and exposes them in profile and onboarding previews. Broken/unknown states block approval. `un ` and exit 1 with empty output remain absent; intermediate/error states remain blocked. Arbitrary stderr or malformed stdout is never retained.

Delivery follows the requested commit push with CI skipped, without Git tags or releases. Automated tests are separate from live acceptance; no owned production artifacts are removed manually.

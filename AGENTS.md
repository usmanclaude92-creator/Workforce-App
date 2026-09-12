# Scoped Development & Minimal Inspection Rule

IMPORTANT: Follow this rule for every instruction and task in this project.

When I ask you to change, fix, improve, test, or add something in a specific section, module, page, feature, component, or workflow:

1. Do NOT inspect, scan, analyze, index, or understand the entire application/codebase.

2. Identify only the files, components, database tables, APIs, services, and dependencies directly relevant to the requested task.

3. Start with the smallest possible scope and expand inspection only when a direct dependency requires it.

4. Do not review unrelated modules, pages, features, folders, or historical code.

5. Do not perform a full application audit unless I explicitly write: “FULL APP AUDIT”.

6. Do not proactively search for additional problems or improvements outside the requested scope.

7. Do not refactor unrelated code.

8. Do not modify existing functionality, UI, architecture, database structures, or workflows outside the requested scope unless absolutely necessary for the requested change.

9. Before starting implementation, briefly determine the minimum set of files and dependencies required for the task.

10. After completing the task, stop. Do not continue inspecting or improving other parts of the application unless I explicitly request it.

## Dependency Rule

If the requested section depends on another file, service, API, database table, shared component, or configuration, inspect only that direct dependency. Continue expanding the scope only when technically required to complete or verify the requested task.

## Development Priority

Minimum inspection → targeted change → targeted testing → stop.

Avoid consuming unnecessary context, tokens, execution time, or quota by performing broad application-wide analysis.

## Full Audit Exception

Only inspect the entire application when I explicitly use the exact instruction:

> FULL APP AUDIT

Otherwise, always assume that the requested task is strictly scoped and work only within the minimum necessary area.

## Completion Report

After every task, provide only:
- Files inspected
- Files changed
- Direct dependencies inspected, if any
- What was changed
- Targeted test/verification performed

Do not start additional work after reporting completion.

This scoped-development rule has higher priority than any general tendency to proactively analyze, audit, refactor, or improve the application.

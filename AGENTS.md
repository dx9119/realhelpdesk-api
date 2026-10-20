# CI/CD Requirements

CRITICAL: You must always verify your changes before finishing a task or committing.
1. Make your code changes.
2. Run `make check-ci` in the terminal using your bash/terminal tool.
3. If the command fails, analyze the output and **REPORT** the errors to the user, along with possible options to fix them.
4. **PAUSE AND WAIT** for the user's explicit permission or choice before applying any fixes. Do NOT auto-fix errors without asking.
5. After applying the approved fix, run `make check-ci` again.
6. Only when `make check-ci` passes with exit code 0, you are allowed to stop or propose a commit.

# Documentation Rules

- Do NOT state test counts in documentation (number of `*Test`/`*IT` classes or test
  methods, e.g. "78 tests"). Such numbers go stale immediately; docs should describe
  structure, rules and commands instead.

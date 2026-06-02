# Contributing to CapturePort

Thank you for your interest in contributing to CapturePort! We welcome contributions to help improve this cross-platform camera and clipboard bridge.

To ensure high code quality, security, and consistent releases, please follow these guidelines.

---

## Code of Conduct

We expect all contributors to adhere to standard professional conduct, treating everyone with respect and empathy.

---

## Development Workflow

1. **Fork and Clone**: Fork the repository on GitHub and clone your fork locally.
2. **Branch Naming Rules**: Create a branch starting with `feat/`, `fix/`, `docs/`, `refactor/`, or `ci/` depending on the scope:
   ```bash
   git checkout -b feat/add-wi-fi-background-listener
   ```
3. **Set Up Environments**:
   - For **PC Receiver**: Install Rust, Node.js (v20+), and your platform's system build dependencies (on Linux, refer to `README.md`). Run `npm install` inside the `pc/` folder.
   - For **Android App**: Open the `android/` folder in Android Studio (compatible with Android 13/14 requirements, Gradle 9.1.0, AGP 8.7.3).
4. **Implement and Test**: Write your features, ensuring clean architecture, strict input validation, and zero resource leaks.
5. **Verify Local Builds**:
   - Run tests for Android: `./gradlew test` inside the `android/` directory.
   - Run tests and linting for PC: `cargo clippy --all-targets` and `cargo test` inside the `pc/src-tauri/` directory, and `npm run check` inside `pc/`.
6. **Submit PR**: Push your branch to your fork and open a Pull Request to our `main` branch.

---

## Commit Message Guidelines

We strictly enforce **Conventional Commits** to automate changelog generation and version tagging. Commits must follow this format:

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### Allowed Types:
- `feat`: A new user-facing feature.
- `fix`: A bug fix.
- `docs`: Documentation-only changes.
- `style`: Changes that do not affect the meaning of the code (white-space, formatting, missing semi-colons, etc.).
- `refactor`: A code change that neither fixes a bug nor adds a feature.
- `perf`: A code change that improves performance.
- `test`: Adding missing tests or correcting existing tests.
- `ci`: Changes to our CI/CD workflows and scripts.
- `chore`: Other changes that don't modify src or test files.

### Examples:
- `feat(android): integrate CameraX native frame capture pipeline`
- `fix(pc): sanitize request_id from websocket connections to prevent path traversal`
- `ci(workflows): add multi-platform tauri build workflow`

---

## Code Standards & Security Disclosures

Because CapturePort operates on local ports, exposes WebSocket endpoints, and interacts with system clipboards, security is our top priority:

- **Strict Validation**: All network inputs (e.g., `request_id`, connection headers) must be sanitized and matched against strict alphanumeric regular expressions.
- **RCE Mitigation**: Avoid running raw unescaped strings in shell interpreters (PowerShell, AppleScript, Bash). Ensure all single/double quotes and command operators are fully escaped.
- **Lifecycle Management**: Wrap all persistent network channels (e.g. OkHttp WebSockets) in robust cleanup triggers (`try-finally` blocks) to prevent background resource leaks.
- **Loopback Bounds**: Ensure that the Model Context Protocol (MCP) server only binds to `127.0.0.1`.

---

## Pull Request Review Checklist

Before merging, all PRs must:
- Pass the automated GitHub Actions validation workflows (Linting, TypeScript checking, Rust Clippy, Rust unit tests, Android unit tests).
- Receive approval from at least one repository maintainer.
- Be squashed into a single clean commit matching the Conventional Commit format.

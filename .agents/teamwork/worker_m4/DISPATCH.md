## 2026-10-02T21:17:58Z
You are worker_m4 for Milestone 4 (Packaging & Windows Standalone Executable).
Your working directory is:
C:\Users\User\.gemini\antigravity\scratch\supermarket-automation\.agents\teamwork\worker_m4
Project root is:
C:\Users\User\.gemini\antigravity\scratch\supermarket-automation

MANDATORY: Read the original user request before starting work:
C:\Users\User\.gemini\antigravity\scratch\supermarket-automation\.agents\teamwork\ORIGINAL_REQUEST.md

Also read:
- C:\Users\User\.gemini\antigravity\scratch\supermarket-automation\.agents\teamwork\orchestrator_2\PROJECT.md
- C:\Users\User\.gemini\antigravity\scratch\supermarket-automation\.agents\teamwork\worker_m3\handoff.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Your mission:
Deliver Milestone 4: Packaging & Windows Standalone Executable.
1. Build the shaded standalone executable fat JAR using `.\mvnw.cmd clean package`.
2. Inspect the resulting JAR in `target/supermarket-automation-1.0.0-shaded.jar`. Ensure:
   - Manifest contains `Main-Class: com.supermarket.Launcher`
   - JavaFX runtime controls, FXML, graphics, and base classes are shaded in
   - SQLite JDBC native libraries and Java driver classes are present
   - Migration scripts (`db/migrations/V1__init_schema.sql`, `V2__seed_initial_catalog.sql`), FXML files, and CSS are present
3. Create a production-ready Windows launch script `run-sas.bat` in the project root:
   - Checks for Java 21+ on PATH (or JAVA_HOME)
   - Checks if `target\supermarket-automation-1.0.0-shaded.jar` exists (and builds it via `mvnw.cmd package -DskipTests` if missing)
   - Launches `java -jar target\supermarket-automation-1.0.0-shaded.jar %*`
   - Provides clear console output, graceful error reporting, and pause on fatal error
4. Ensure standalone execution runs cleanly on Windows:
   - Verify `Launcher` can be invoked with headless smoke test or version/help flags
   - Enhance `Launcher.java` or `SupermarketApp.java` if needed so that passing `--help` or `--smoke-test` / `--version` prints application metadata, verifies SQLite database connectivity, and exits cleanly with status 0 without needing a display screen.
   - Run `java -jar target\supermarket-automation-1.0.0-shaded.jar --smoke-test` (or `--version`) to empirically verify the standalone executable JAR works on Windows!
5. Ensure `.\mvnw.cmd test` continues to pass 100% (185+ tests).
6. Document findings and evidence in `report.md` and `handoff.md` in your working directory, and send a completion message back to orchestrator_2.

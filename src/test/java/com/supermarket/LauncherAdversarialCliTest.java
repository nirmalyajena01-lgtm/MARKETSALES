package com.supermarket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Milestone 4 Adversarial CLI & Packaging Stress Tests")
class LauncherAdversarialCliTest {

    private record CliResult(int exitCode, String stdout, String stderr) {}

    private CliResult executeCli(String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int code = Launcher.runCli(args, out, err);
        return new CliResult(code, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("1. Standard and Case-Insensitive Flag Variations")
    class FlagVariationsTest {

        @ParameterizedTest(name = "Help flag variation: {0}")
        @ValueSource(strings = {"--help", "-h", "-?", "--HELP", "--Help", "-H"})
        void testHelpFlagVariations(String flag) {
            CliResult res = executeCli(flag);
            assertThat(res.exitCode()).isEqualTo(0);
            assertThat(res.stdout()).contains("Usage: java -jar supermarket-automation-1.0.0-shaded.jar");
            assertThat(res.stderr()).isEmpty();
        }

        @ParameterizedTest(name = "Version flag variation: {0}")
        @ValueSource(strings = {"--version", "-v", "--VERSION", "--Version", "-V"})
        void testVersionFlagVariations(String flag) {
            CliResult res = executeCli(flag);
            assertThat(res.exitCode()).isEqualTo(0);
            assertThat(res.stdout())
                    .contains("Supermarket Automation Software (SAS) v1.0.0")
                    .contains("Main-Class       : com.supermarket.Launcher");
            assertThat(res.stderr()).isEmpty();
        }

        @ParameterizedTest(name = "Smoke test flag variation: {0}")
        @ValueSource(strings = {"--smoke-test", "-s", "--SMOKE-TEST", "--Smoke-Test", "-S"})
        void testSmokeTestFlagVariations(String flag) {
            CliResult res = executeCli(flag);
            assertThat(res.exitCode()).isEqualTo(0);
            assertThat(res.stdout())
                    .contains("SMOKE TEST RESULT: PASSED (All systems operational)")
                    .contains("[1/5] Testing SQLite JDBC Driver loading... OK")
                    .contains("[2/5] Testing SQLite Database & Migration Runner... OK")
                    .contains("[3/5] Testing Product Catalog queries... OK")
                    .contains("[4/5] Testing Billing Service... OK")
                    .contains("[5/5] Testing Checkout & Monospace Thermal Receipt Formatter... OK");
            assertThat(res.stderr()).isEmpty();
        }
    }

    @Nested
    @DisplayName("2. Unknown and Malformed Command-Line Options")
    class UnknownFlagsTest {

        @ParameterizedTest(name = "Unknown option: {0}")
        @ValueSource(strings = {
                "--invalid-flag",
                "-x",
                "--foo",
                "--bar",
                "-",
                "--",
                "---unknown",
                "-9",
                "--help=true",
                "--version=1.0",
                "--smoke-test=false",
                "/help",
                "/?",
                "-unknown"
        })
        void testUnknownOptionsReturnExitCodeOne(String flag) {
            CliResult res = executeCli(flag);
            assertThat(res.exitCode()).isEqualTo(1);
            assertThat(res.stderr())
                    .contains("Unknown option: " + flag)
                    .contains("Usage: java -jar");
            assertThat(res.stdout()).isEmpty();
        }
    }

    @Nested
    @DisplayName("3. Multi-Argument Combinations & Precedence")
    class MultiArgumentCombinationsTest {

        @Test
        @DisplayName("First argument --version takes precedence over trailing --help")
        void testVersionPrecedenceOverHelp() {
            CliResult res = executeCli("--version", "--help");
            assertThat(res.exitCode()).isEqualTo(0);
            assertThat(res.stdout()).contains("Supermarket Automation Software (SAS) v1.0.0");
            assertThat(res.stdout()).doesNotContain("Usage: java -jar");
            assertThat(res.stderr()).isEmpty();
        }

        @Test
        @DisplayName("First argument --help takes precedence over trailing --version")
        void testHelpPrecedenceOverVersion() {
            CliResult res = executeCli("--help", "--version");
            assertThat(res.exitCode()).isEqualTo(0);
            assertThat(res.stdout()).contains("Usage: java -jar");
            assertThat(res.stderr()).isEmpty();
        }

        @Test
        @DisplayName("First argument --smoke-test takes precedence over trailing flags")
        void testSmokeTestPrecedenceOverTrailing() {
            CliResult res = executeCli("--smoke-test", "--invalid-flag");
            assertThat(res.exitCode()).isEqualTo(0);
            assertThat(res.stdout()).contains("SMOKE TEST RESULT: PASSED");
            assertThat(res.stderr()).isEmpty();
        }

        @Test
        @DisplayName("First argument --invalid-flag fails immediately with exit code 1")
        void testInvalidFlagFirstFailsImmediately() {
            CliResult res = executeCli("--invalid-flag", "--smoke-test");
            assertThat(res.exitCode()).isEqualTo(1);
            assertThat(res.stderr()).contains("Unknown option: --invalid-flag");
            assertThat(res.stdout()).isEmpty();
        }
    }

    @Nested
    @DisplayName("4. Input Boundary Conditions and Edge Cases")
    class BoundaryConditionsTest {

        @Test
        @DisplayName("Null arguments array returns -1 (signals GUI launch to main)")
        void testNullArguments() {
            CliResult res = executeCli((String[]) null);
            assertThat(res.exitCode()).isEqualTo(-1);
            assertThat(res.stdout()).isEmpty();
            assertThat(res.stderr()).isEmpty();
        }

        @Test
        @DisplayName("Empty arguments array returns -1 (signals GUI launch to main)")
        void testEmptyArgumentsArray() {
            CliResult res = executeCli(new String[0]);
            assertThat(res.exitCode()).isEqualTo(-1);
            assertThat(res.stdout()).isEmpty();
            assertThat(res.stderr()).isEmpty();
        }

        @Test
        @DisplayName("Empty string argument returns exit code 1")
        void testEmptyStringArgument() {
            CliResult res = executeCli("");
            assertThat(res.exitCode()).isEqualTo(1);
            assertThat(res.stderr()).contains("Unknown option: ");
        }

        @Test
        @DisplayName("Whitespace string argument returns exit code 1")
        void testWhitespaceArgument() {
            CliResult res = executeCli("   ");
            assertThat(res.exitCode()).isEqualTo(1);
            assertThat(res.stderr()).contains("Unknown option:    ");
        }

        @Test
        @DisplayName("Positional argument without hyphen returns exit code 1")
        void testPositionalArgument() {
            CliResult res = executeCli("pos");
            assertThat(res.exitCode()).isEqualTo(1);
            assertThat(res.stderr()).contains("Unknown option: pos");
        }
    }

    @Nested
    @DisplayName("5. isCliCommand Edge Discrimination")
    class IsCliCommandDiscriminationTest {

        @Test
        @DisplayName("Correctly rejects non-CLI and blank arguments")
        void testRejectsNonCli() {
            assertThat(Launcher.isCliCommand(null)).isFalse();
            assertThat(Launcher.isCliCommand(new String[0])).isFalse();
            assertThat(Launcher.isCliCommand(new String[]{""})).isFalse();
            assertThat(Launcher.isCliCommand(new String[]{"   "})).isFalse();
            assertThat(Launcher.isCliCommand(new String[]{"inventory"})).isFalse();
        }

        @Test
        @DisplayName("Correctly accepts hyphenated options")
        void testAcceptsHyphenatedOptions() {
            assertThat(Launcher.isCliCommand(new String[]{"-s"})).isTrue();
            assertThat(Launcher.isCliCommand(new String[]{"--smoke-test"})).isTrue();
            assertThat(Launcher.isCliCommand(new String[]{"-v"})).isTrue();
            assertThat(Launcher.isCliCommand(new String[]{"--version"})).isTrue();
            assertThat(Launcher.isCliCommand(new String[]{"-h"})).isTrue();
            assertThat(Launcher.isCliCommand(new String[]{"--help"})).isTrue();
            assertThat(Launcher.isCliCommand(new String[]{"-?"})).isTrue();
            assertThat(Launcher.isCliCommand(new String[]{"-x"})).isTrue();
            assertThat(Launcher.isCliCommand(new String[]{"--unknown"})).isTrue();
            assertThat(Launcher.isCliCommand(new String[]{"-"})).isTrue();
            assertThat(Launcher.isCliCommand(new String[]{"--"})).isTrue();
        }
    }

    @Nested
    @DisplayName("6. Standalone Smoke Test Unmocked Persistence & Business Engine")
    class SmokeTestExecutionPurityTest {

        @Test
        @DisplayName("Smoke test runs against real unmocked SQLite in-memory DB and passes all 5 stages")
        void testSmokeTestPurity() {
            ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
            ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
            PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

            int exitCode = Launcher.runSmokeTest(out, err);

            assertThat(exitCode).isEqualTo(0);
            String output = outBytes.toString(StandardCharsets.UTF_8);

            // Stage 1
            assertThat(output).contains("[1/5] Testing SQLite JDBC Driver loading... OK (org.sqlite.JDBC loaded)");
            // Stage 2
            assertThat(output).contains("[2/5] Testing SQLite Database & Migration Runner... OK (Schema V3 applied successfully)");
            // Stage 3
            assertThat(output).contains("[3/5] Testing Product Catalog queries... OK (27 catalog items loaded)");
            // Stage 4
            assertThat(output).contains("[4/5] Testing Billing Service... OK (Cart computed subtotal: ₹662.34)");
            // Stage 5
            assertThat(output).contains("[5/5] Testing Checkout & Monospace Thermal Receipt Formatter... OK (Receipt ");
            // Final Summary
            assertThat(output).contains("SMOKE TEST RESULT: PASSED (All systems operational)");

            // No errors
            assertThat(errBytes.toString(StandardCharsets.UTF_8)).isEmpty();
        }
    }
}

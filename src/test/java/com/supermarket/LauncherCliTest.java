package com.supermarket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class LauncherCliTest {

    @Test
    @DisplayName("Launcher --help displays command-line options and returns 0")
    void testHelpLongFlag() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode = Launcher.runCli(new String[]{"--help"}, out, err);

        assertThat(exitCode).isEqualTo(0);
        String output = outBytes.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .contains("Supermarket Automation Software (SAS)")
                .contains("Usage: java -jar supermarket-automation-1.0.0-shaded.jar")
                .contains("--smoke-test")
                .contains("--version")
                .contains("--help");
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    @DisplayName("Launcher -h displays command-line options and returns 0")
    void testHelpShortFlag() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode = Launcher.runCli(new String[]{"-h"}, out, err);

        assertThat(exitCode).isEqualTo(0);
        String output = outBytes.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Usage: java -jar");
    }

    @Test
    @DisplayName("Launcher --version displays metadata and returns 0")
    void testVersionLongFlag() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode = Launcher.runCli(new String[]{"--version"}, out, err);

        assertThat(exitCode).isEqualTo(0);
        String output = outBytes.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .contains("Supermarket Automation Software (SAS) v1.0.0")
                .contains("Main-Class       : com.supermarket.Launcher")
                .contains("Java Runtime")
                .contains("Operating System")
                .contains("Default DB Path");
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    @DisplayName("Launcher -v displays metadata and returns 0")
    void testVersionShortFlag() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode = Launcher.runCli(new String[]{"-v"}, out, err);

        assertThat(exitCode).isEqualTo(0);
        String output = outBytes.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Supermarket Automation Software (SAS) v1.0.0");
    }

    @Test
    @DisplayName("Launcher option parsing is independent of the default locale")
    void testUppercaseOptionWithTurkishLocale() {
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
            ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
            PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

            int exitCode = Launcher.runCli(new String[]{"--VERSION"}, out, err);

            assertThat(exitCode).isEqualTo(0);
            assertThat(outBytes.toString(StandardCharsets.UTF_8))
                    .contains("Supermarket Automation Software (SAS) v1.0.0");
            assertThat(errBytes.toString(StandardCharsets.UTF_8)).isEmpty();
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    @DisplayName("Launcher --smoke-test verifies SQLite, migrations, catalog, and receipt generation")
    void testSmokeTestLongFlag() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode = Launcher.runSmokeTest(out, err);

        assertThat(exitCode).isEqualTo(0);
        String output = outBytes.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .contains("[1/5] Testing SQLite JDBC Driver loading... OK")
                .contains("[2/5] Testing SQLite Database & Migration Runner... OK")
                .contains("[3/5] Testing Product Catalog queries... OK")
                .contains("[4/5] Testing Billing Service... OK")
                .contains("[5/5] Testing Checkout & Monospace Thermal Receipt Formatter... OK")
                .contains("SMOKE TEST RESULT: PASSED (All systems operational)");
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    @DisplayName("Launcher -s executes smoke test cleanly")
    void testSmokeTestShortFlag() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode = Launcher.runCli(new String[]{"-s"}, out, err);

        assertThat(exitCode).isEqualTo(0);
        String output = outBytes.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("SMOKE TEST RESULT: PASSED");
    }

    @Test
    @DisplayName("Launcher returns 1 on unknown command-line options")
    void testUnknownFlag() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode = Launcher.runCli(new String[]{"--invalid-flag"}, out, err);

        assertThat(exitCode).isEqualTo(1);
        String errOutput = errBytes.toString(StandardCharsets.UTF_8);
        assertThat(errOutput).contains("Unknown option: --invalid-flag");
    }

    @Test
    @DisplayName("Launcher isCliCommand accurately discriminates CLI arguments")
    void testIsCliCommand() {
        assertThat(Launcher.isCliCommand(null)).isFalse();
        assertThat(Launcher.isCliCommand(new String[0])).isFalse();
        assertThat(Launcher.isCliCommand(new String[]{"pos"})).isFalse();

        assertThat(Launcher.isCliCommand(new String[]{"--help"})).isTrue();
        assertThat(Launcher.isCliCommand(new String[]{"-h"})).isTrue();
        assertThat(Launcher.isCliCommand(new String[]{"--smoke-test"})).isTrue();
        assertThat(Launcher.isCliCommand(new String[]{"--version"})).isTrue();
    }

    @Test
    @DisplayName("Launcher runCli returns -1 for empty or null arguments indicating GUI launch")
    void testRunCliEmptyArgsReturnsNegativeOne() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        assertThat(Launcher.runCli(null, out, err)).isEqualTo(-1);
        assertThat(Launcher.runCli(new String[0], out, err)).isEqualTo(-1);
    }
}

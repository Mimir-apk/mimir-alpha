package com.gruning.mimir.tweaker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Non-interactive entry point for the qualification signer.
 *
 * The stock {@code main()} builds its PasswordReader from {@link System#console()}, so it fails
 * closed with CONSOLE_REQUIRED whenever there is no TTY -- which is every automated or piped
 * invocation. {@code runCli} itself takes an arbitrary {@link
 * QualificationManifestGenerator.PasswordReader}, so the TTY was never a property of the signer,
 * only of that one entry point.
 *
 * This reads the passphrase from stdin instead. Key material therefore arrives on a pipe and never
 * touches argv, the environment, a file, or shell history. Everything else -- request grammar,
 * monotonic sequence, output-directory reservation -- is the untouched generator.
 *
 * Usage:
 *   printf '%s' "$PASSPHRASE" | java -cp <classes> com.gruning.mimir.tweaker.QualificationSignFromStdin \
 *     --request <request.v2> --keystore <key.p12> --output-dir <new-dir>
 *
 * A trailing newline is tolerated and stripped, so `pbpaste |`, a password manager, or a heredoc
 * all work. Anything else is passed through verbatim.
 */
public final class QualificationSignFromStdin {

    private QualificationSignFromStdin() {
    }

    public static void main(String[] args) {
        char[] password;
        try {
            password = readPassword(System.in);
        } catch (Exception failure) {
            System.err.println("PASSWORD_READ_FAILED");
            System.exit(2);
            return;
        }
        if (password.length == 0) {
            System.err.println("PASSWORD_EMPTY: pipe the passphrase to stdin");
            System.exit(2);
            return;
        }
        // runCli zeroes the array it is handed; keep no other reference to it.
        int result = QualificationManifestGenerator.runCli(
            args,
            () -> password,
            Path.of("").toAbsolutePath().normalize(),
            System.out,
            System.err
        );
        System.exit(result);
    }

    private static char[] readPassword(InputStream source) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[256];
        int read;
        while ((read = source.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        Arrays.fill(chunk, (byte) 0);
        byte[] raw = buffer.toByteArray();
        int length = raw.length;
        // Strip exactly one trailing newline (and its CR), so `echo` and heredocs behave.
        if (length > 0 && raw[length - 1] == '\n') {
            length--;
            if (length > 0 && raw[length - 1] == '\r') {
                length--;
            }
        }
        String text = new String(raw, 0, length, StandardCharsets.UTF_8);
        Arrays.fill(raw, (byte) 0);
        char[] password = text.toCharArray();
        return password;
    }
}

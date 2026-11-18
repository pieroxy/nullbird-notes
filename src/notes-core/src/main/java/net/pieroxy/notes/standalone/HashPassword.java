package net.pieroxy.notes.standalone;

import com.google.gson.GsonBuilder;
import net.pieroxy.notes.config.PasswordHasher;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Prints the hashed password object (algorithm, iterations, salt, hash) to paste as a user's
 * {@code password} in credentials.json. Prompts twice on a terminal; reads one line from stdin
 * otherwise. Only the JSON goes to stdout.
 */
final class HashPassword {
  private HashPassword() {}

  static void run() throws IOException {
    String password;
    Console console = System.console();
    if (console != null) {
      char[] first = console.readPassword("Password: ");
      char[] second = console.readPassword("Again: ");
      if (first == null || second == null || !Arrays.equals(first, second)) {
        System.err.println("Passwords don't match.");
        System.exit(1);
        return;
      }
      password = new String(first);
    } else {
      password = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
    }
    if (password == null || password.isEmpty()) {
      System.err.println("Empty password.");
      System.exit(1);
      return;
    }
    System.err.println("Paste this as the user's \"password\" in credentials.json:");
    System.out.println(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(PasswordHasher.hash(password)));
  }
}

package net.pieroxy.notes.storage;

/** A commit expected another head revision than the note's: someone else committed in between. */
public class StaleRevisionException extends Exception {
  public StaleRevisionException(String noteId, int expected, int actual) {
    super("Note " + noteId + " is at revision " + actual + ", expected " + expected);
  }
}

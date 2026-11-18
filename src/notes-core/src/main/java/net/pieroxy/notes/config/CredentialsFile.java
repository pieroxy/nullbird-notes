package net.pieroxy.notes.config;

import java.util.List;

/** credentials.json. */
public class CredentialsFile {
  private List<UserCredential> users;

  public List<UserCredential> getUsers() {
    return users;
  }

  public void setUsers(List<UserCredential> users) {
    this.users = users;
  }
}

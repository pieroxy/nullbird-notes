package net.pieroxy.notes.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** The server in use, and changing it. */
public final class SettingsActivity extends Activity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_settings);
    WebApp webApp = NotesApp.of(this).webApp();
    if (webApp == null) {
      startActivity(new Intent(this, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      finish();
      return;
    }
    ((TextView) findViewById(R.id.server)).setText(webApp.server);
    ((TextView) findViewById(R.id.build)).setText(getString(R.string.settings_build, webApp.current().build()));
    findViewById(R.id.open).setOnClickListener(v -> {
      startActivity(MainActivity.restart(this));
      finish();
    });
    findViewById(R.id.change).setOnClickListener(v -> new AlertDialog.Builder(this)
        .setTitle(R.string.settings_change_title)
        .setMessage(R.string.settings_change_message)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.settings_change_confirm, (dialog, which) -> {
          NotesApp.of(this).forgetServer();
          startActivity(new Intent(this, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
          finish();
        })
        .show());
  }
}

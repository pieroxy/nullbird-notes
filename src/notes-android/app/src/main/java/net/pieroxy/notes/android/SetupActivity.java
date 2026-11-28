package net.pieroxy.notes.android;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;

/** Asks for the server's address, checks it's a Notes server, and downloads its web app. */
public final class SetupActivity extends Activity {
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private EditText address;
  private Button connect;
  private ProgressBar progress;
  private TextView status;
  private boolean destroyed;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_setup);
    address = findViewById(R.id.address);
    connect = findViewById(R.id.connect);
    progress = findViewById(R.id.progress);
    status = findViewById(R.id.status);
    connect.setOnClickListener(v -> start());
    address.setOnEditorActionListener((v, actionId, event) -> {
      boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
      if (actionId == EditorInfo.IME_ACTION_GO || enter) {
        start();
        return true;
      }
      return false;
    });
  }

  private void start() {
    String server;
    try {
      server = ServerAddress.normalize(address.getText().toString());
    } catch (IllegalArgumentException e) {
      show(e.getMessage(), false);
      return;
    }
    address.setText(server);
    show(getString(R.string.setup_connecting), true);
    NotesApp app = NotesApp.of(this);
    executor.execute(() -> {
      String error;
      try {
        AppManifest manifest = app.store().fetchManifest(server);
        main.post(() -> show(getString(R.string.setup_downloading), true));
        WebAppCopy copy = app.store().download(server, manifest);
        app.install(server, copy);
        main.post(this::done);
        return;
      } catch (AppManifest.InvalidManifestException | Fetcher.NotFoundException e) {
        error = getString(R.string.setup_not_notes);
      } catch (SSLException e) {
        error = getString(R.string.setup_certificate);
      } catch (UnknownHostException | ConnectException | SocketTimeoutException e) {
        error = getString(R.string.setup_unreachable);
      } catch (WebAppStore.CorruptDownloadException e) {
        error = getString(R.string.setup_corrupt);
      } catch (IOException e) {
        error = getString(R.string.setup_failed, String.valueOf(e.getMessage()));
      }
      String message = error;
      main.post(() -> show(message, false));
    });
  }

  private void done() {
    if (destroyed) return;
    startActivity(MainActivity.restart(this));
    finish();
  }

  private void show(String message, boolean working) {
    if (destroyed) return;
    status.setText(message);
    status.setVisibility(message == null ? View.GONE : View.VISIBLE);
    progress.setVisibility(working ? View.VISIBLE : View.GONE);
    connect.setEnabled(!working);
    address.setEnabled(!working);
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    executor.shutdown();
    super.onDestroy();
  }
}

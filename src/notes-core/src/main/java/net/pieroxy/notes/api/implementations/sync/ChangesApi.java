package net.pieroxy.notes.api.implementations.sync;

import net.pieroxy.notes.api.metadata.AbstractAsyncAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.sync.ChangesPage;

import java.util.concurrent.CompletableFuture;

/**
 * The notes changed after a cursor. With {@code wait}, a long poll: answers as soon as there's
 * something to report, or after {@link #LONG_POLL_MILLIS} with nothing new, under the 60 s read
 * timeout reverse proxies commonly default to.
 * No redraw: the sync engine redraws when there are changes, not every time a long poll ends.
 */
@Endpoint(method = ApiMethod.GET, redraw = false)
public class ChangesApi extends AbstractAsyncAuthenticatedEndpoint<ChangesApiInput, ChangesPage> {
  static final long LONG_POLL_MILLIS = 25_000;

  public ChangesApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public CompletableFuture<ChangesPage> processAuthenticatedAsync(ChangesApiInput input) throws Exception {
    if (Boolean.TRUE.equals(input.getWait())) {
      return serviceProvider.getNoteService().awaitChanges(input.getUsername(), input.getSince(), LONG_POLL_MILLIS);
    }
    return CompletableFuture.completedFuture(serviceProvider.getNoteService().changes(input.getUsername(), input.getSince()));
  }
}

@TypeScriptType
class ChangesApiInput extends AuthenticatedApiInput {
  /** The cursor: the seq of the last page received, 0 for everything. */
  @Required
  private long since;
  /** Wait for changes when there are none yet. */
  private Boolean wait;

  public long getSince() {
    return since;
  }

  public void setSince(long since) {
    this.since = since;
  }

  public Boolean getWait() {
    return wait;
  }

  public void setWait(Boolean wait) {
    this.wait = wait;
  }
}

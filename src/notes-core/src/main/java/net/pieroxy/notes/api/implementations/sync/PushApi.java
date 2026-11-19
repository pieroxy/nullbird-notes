package net.pieroxy.notes.api.implementations.sync;

import net.pieroxy.notes.api.metadata.AbstractAuthenticatedEndpoint;
import net.pieroxy.notes.api.metadata.ApiMethod;
import net.pieroxy.notes.api.metadata.AuthenticatedApiInput;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.services.IServiceProvider;
import net.pieroxy.notes.services.NoteService;
import net.pieroxy.notes.sync.NoteChange;
import net.pieroxy.notes.sync.PushResult;

import java.util.ArrayList;
import java.util.List;

/** Applies changes in order, each on its own: one rejected change doesn't stop the next. */
@Endpoint(method = ApiMethod.POST)
public class PushApi extends AbstractAuthenticatedEndpoint<PushApiInput, PushApiOutput> {
  static final int MAX_CHANGES_PER_PUSH = 100;

  public PushApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public PushApiOutput processAuthenticated(PushApiInput input) throws Exception {
    if (input.getChanges().size() > MAX_CHANGES_PER_PUSH) {
      throw new InvalidInputException("At most " + MAX_CHANGES_PER_PUSH + " changes per push.");
    }
    NoteService notes = serviceProvider.getNoteService();
    List<PushResult> results = new ArrayList<>();
    for (NoteChange change : input.getChanges()) {
      results.add(notes.push(input.getUsername(), input.getDeviceId(), change));
    }
    return new PushApiOutput(results);
  }
}

@TypeScriptType
class PushApiInput extends AuthenticatedApiInput {
  /** Generated once per device: recorded with each change. */
  @Required
  private String deviceId;
  @Required
  private List<NoteChange> changes;

  public String getDeviceId() {
    return deviceId;
  }

  public void setDeviceId(String deviceId) {
    this.deviceId = deviceId;
  }

  public List<NoteChange> getChanges() {
    return changes;
  }

  public void setChanges(List<NoteChange> changes) {
    this.changes = changes;
  }
}

@TypeScriptType
class PushApiOutput {
  /** One per change, in the same order. */
  @Required
  private List<PushResult> results;

  public PushApiOutput() {
  }

  public PushApiOutput(List<PushResult> results) {
    this.results = results;
  }

  public List<PushResult> getResults() {
    return results;
  }

  public void setResults(List<PushResult> results) {
    this.results = results;
  }
}

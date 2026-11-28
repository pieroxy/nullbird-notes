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
import net.pieroxy.notes.sync.MetaChange;
import net.pieroxy.notes.sync.NoteDefaults;
import net.pieroxy.notes.sync.NoteChange;
import net.pieroxy.notes.sync.PushResult;
import net.pieroxy.notes.sync.TagOptions;
import net.pieroxy.notes.sync.TagOptionsChange;
import net.pieroxy.notes.sync.TagOrderChange;
import net.pieroxy.notes.sync.TagOrderResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies changes in order, each on its own: one rejected change doesn't stop the next. Text
 * changes come first, so that metadata changes can apply to notes created in the same push; the
 * tag order last, so that it finds the tags those metadata changes add.
 */
@Endpoint(method = ApiMethod.POST)
public class PushApi extends AbstractAuthenticatedEndpoint<PushApiInput, PushApiOutput> {
  static final int MAX_CHANGES_PER_PUSH = 100;

  public PushApi(IServiceProvider serviceProvider) {
    super(serviceProvider);
  }

  @Override
  public PushApiOutput processAuthenticated(PushApiInput input) throws Exception {
    List<MetaChange> metaChanges = input.getMetaChanges() == null ? List.of() : input.getMetaChanges();
    // Before anything is applied: a refusal after would leave the push half done.
    if (input.getTagOptions() != null && input.getTagOptions().size() > NoteService.MAX_TAG_OPTIONS_CHANGES) {
      throw new InvalidInputException("At most " + NoteService.MAX_TAG_OPTIONS_CHANGES + " tag options changes per push.");
    }
    if (input.getChanges().size() > MAX_CHANGES_PER_PUSH || metaChanges.size() > MAX_CHANGES_PER_PUSH) {
      throw new InvalidInputException("At most " + MAX_CHANGES_PER_PUSH + " changes and " + MAX_CHANGES_PER_PUSH
          + " metadata changes per push.");
    }
    NoteService notes = serviceProvider.getNoteService();
    List<PushResult> results = new ArrayList<>();
    for (NoteChange change : input.getChanges()) {
      results.add(notes.push(input.getUsername(), input.getDeviceId(), change));
    }
    List<PushResult> metaResults = new ArrayList<>();
    for (MetaChange change : metaChanges) {
      metaResults.add(notes.pushMeta(input.getUsername(), input.getDeviceId(), change, input.getSince()));
    }
    TagOrderResult tagOrder = input.getTagOrder() == null ? null : notes.pushTagOrder(input.getUsername(), input.getTagOrder());
    Map<String, TagOptions> tagOptions = input.getTagOptions() == null ? null
        : notes.pushTagOptions(input.getUsername(), input.getTagOptions(), input.getSince());
    NoteDefaults noteDefaults = input.getNoteDefaults() == null ? null : notes.pushNoteDefaults(input.getUsername(), input.getNoteDefaults());
    PushApiOutput output = new PushApiOutput(results, metaResults, tagOrder);
    output.setTagOptions(tagOptions);
    output.setNoteDefaults(noteDefaults);
    return output;
  }
}

@TypeScriptType
class PushApiInput extends AuthenticatedApiInput {
  /** Generated once per device: recorded with each change. */
  @Required
  private String deviceId;
  @Required
  private List<NoteChange> changes;
  private List<MetaChange> metaChanges;
  /** Absent: the order doesn't change. */
  private TagOrderChange tagOrder;
  /** Absent: no tag's options change. */
  private List<TagOptionsChange> tagOptions;

  public List<TagOptionsChange> getTagOptions() {
    return tagOptions;
  }

  public void setTagOptions(List<TagOptionsChange> tagOptions) {
    this.tagOptions = tagOptions;
  }

  /** Absent: the user's defaults for their notes don't change. */
  private NoteDefaults noteDefaults;

  public NoteDefaults getNoteDefaults() {
    return noteDefaults;
  }

  public void setNoteDefaults(NoteDefaults noteDefaults) {
    this.noteDefaults = noteDefaults;
  }

  /** The client's cursor: its metadata changes follow the tags renamed or removed since (see NoteService#pushMeta). */
  private Long since;

  public Long getSince() {
    return since;
  }

  public void setSince(Long since) {
    this.since = since;
  }

  public TagOrderChange getTagOrder() {
    return tagOrder;
  }

  public void setTagOrder(TagOrderChange tagOrder) {
    this.tagOrder = tagOrder;
  }

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

  public List<MetaChange> getMetaChanges() {
    return metaChanges;
  }

  public void setMetaChanges(List<MetaChange> metaChanges) {
    this.metaChanges = metaChanges;
  }
}

@TypeScriptType
class PushApiOutput {
  /** One per change, in the same order. */
  @Required
  private List<PushResult> results;
  /** One per metadata change, in the same order. */
  @Required
  private List<PushResult> metaResults;
  /** Absent if no tag order was sent. */
  private TagOrderResult tagOrder;
  /** Every tag's options now; absent if no tag options were sent. */
  private Map<String, TagOptions> tagOptions;

  public Map<String, TagOptions> getTagOptions() {
    return tagOptions;
  }

  public void setTagOptions(Map<String, TagOptions> tagOptions) {
    this.tagOptions = tagOptions;
  }

  /** The user's defaults for their notes now; absent if none were sent. */
  private NoteDefaults noteDefaults;

  public NoteDefaults getNoteDefaults() {
    return noteDefaults;
  }

  public void setNoteDefaults(NoteDefaults noteDefaults) {
    this.noteDefaults = noteDefaults;
  }

  public PushApiOutput() {
  }

  public PushApiOutput(List<PushResult> results, List<PushResult> metaResults, TagOrderResult tagOrder) {
    this.results = results;
    this.metaResults = metaResults;
    this.tagOrder = tagOrder;
  }

  public TagOrderResult getTagOrder() {
    return tagOrder;
  }

  public void setTagOrder(TagOrderResult tagOrder) {
    this.tagOrder = tagOrder;
  }

  public List<PushResult> getResults() {
    return results;
  }

  public void setResults(List<PushResult> results) {
    this.results = results;
  }

  public List<PushResult> getMetaResults() {
    return metaResults;
  }

  public void setMetaResults(List<PushResult> metaResults) {
    this.metaResults = metaResults;
  }
}

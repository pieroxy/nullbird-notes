package net.pieroxy.notes.services;

/**
 * Read access to the running services. {@link ServiceProvider} implements this without
 * restriction; a {@link Service#init} receives a view narrowed to that service's own
 * {@link Service#getDependencies()} instead. Depend on this interface rather than the concrete
 * class unless you need {@link ServiceProvider#init()}/{@link ServiceProvider#destroy()}.
 */
public interface IServiceProvider {
  SettingsService getSettingsService();

  SessionService getSessionService();

  ClientGuardService getClientGuardService();

  NoteStoreService getNoteStoreService();

  NoteService getNoteService();

  ImportService getImportService();

  FontService getFontService();

  WebServerService getWebServerService();
}

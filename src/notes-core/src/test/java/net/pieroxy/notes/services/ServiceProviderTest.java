package net.pieroxy.notes.services;

import net.pieroxy.notes.config.Configuration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertSame;

/**
 * {@link ServiceProvider#scopedFor}: the view a {@link Service#init} actually receives is
 * restricted to what it declared via {@link Service#getDependencies()} — see
 * {@link ServiceOrderingTest} for the topological-ordering half of the same mechanism.
 */
public class ServiceProviderTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private static class FakeService implements Service {
    private final List<Class<? extends Service>> dependencies;

    FakeService(List<Class<? extends Service>> dependencies) {
      this.dependencies = dependencies;
    }

    @Override
    public List<Class<? extends Service>> getDependencies() {
      return dependencies;
    }
  }

  /** Never init()ed: nothing touches disk or network. */
  private ServiceProvider buildServiceProvider() {
    SettingsService settingsService = new SettingsService(new Configuration(), new File(tmp.getRoot(), "credentials.json"), tmp.getRoot());
    return new ServiceProvider(settingsService, new SessionService(), new ClientGuardService(), new NoteStoreService(), new NoteService(),
        new ImportService(), new FontService(), new WebServerService(), new SecondFactorService());
  }

  @Test
  public void scopedViewExposesADeclaredDependency() {
    ServiceProvider serviceProvider = buildServiceProvider();
    IServiceProvider scoped = serviceProvider.scopedFor(new FakeService(List.of(SettingsService.class)));

    assertSame(serviceProvider.getSettingsService(), scoped.getSettingsService());
  }

  @Test(expected = IllegalStateException.class)
  public void scopedViewThrowsForAnUndeclaredDependency() {
    ServiceProvider serviceProvider = buildServiceProvider();
    IServiceProvider scoped = serviceProvider.scopedFor(new FakeService(List.of(SettingsService.class)));

    scoped.getSessionService();
  }

  @Test(expected = IllegalStateException.class)
  public void scopedViewWithNoDeclaredDependenciesExposesNothing() {
    ServiceProvider serviceProvider = buildServiceProvider();
    IServiceProvider scoped = serviceProvider.scopedFor(new FakeService(List.of()));

    scoped.getSettingsService();
  }
}

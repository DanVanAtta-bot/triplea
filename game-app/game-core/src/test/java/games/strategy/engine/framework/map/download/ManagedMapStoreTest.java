package games.strategy.engine.framework.map.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import games.strategy.engine.framework.map.file.system.loader.InstalledMapsListing;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import java.util.List;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.NonNls;
import org.junit.jupiter.api.Test;
import org.triplea.http.client.lobby.maps.listing.MapDownloadItem;

/**
 * Own-logic tests for how {@link ManagedMapStore} reacts to a completed download. The bug behind
 * #14628 is purely a state-model decision — whether {@code downloadComplete} tolerates a download
 * that is not in the store — so it is exercised on a real store with real values, without a window,
 * a {@link DownloadCoordinator} broadcast, or an actual file download. Nothing here needs a double.
 */
class ManagedMapStoreTest extends AbstractClientSettingTestCase {
  @NonNls private static final String LISTED_MAP_NAME = "listed_map";
  @NonNls private static final String UNLISTED_MAP_NAME = "unlisted_map";

  private static MapDownloadItem newMap(final String mapName) {
    return MapDownloadItem.builder()
        .downloadUrl("")
        .previewImageUrl("")
        .mapName(mapName)
        .description("description")
        .lastCommitDateEpochMilli(10L)
        .downloadSizeInBytes(750L)
        .build();
  }

  /**
   * Regression for #14628: a store built from a degraded (empty) listing must not throw when a
   * download it never knew about completes, and must not silently invent an entry for it.
   */
  @Test
  void downloadCompleteForMapAbsentFromListingDoesNotThrowOrAddIt() {
    final ManagedMapStore store = new ManagedMapStore();
    store.initialize(List.of(), new InstalledMapsListing(List.of()));

    final MapDownloadItem unlistedMap = newMap(UNLISTED_MAP_NAME);

    assertThatCode(() -> store.downloadComplete(unlistedMap)).doesNotThrowAnyException();
    assertThat(store.getMapByName(UNLISTED_MAP_NAME)).isEmpty();
  }

  @Test
  void downloadCompleteForListedMapFlipsItToInstalled() throws Exception {
    final MapDownloadItem listedMap = newMap(LISTED_MAP_NAME);
    final ManagedMapStore store = new ManagedMapStore();
    store.initialize(List.of(listedMap), new InstalledMapsListing(List.of()));
    assertThat(store.getMapByName(LISTED_MAP_NAME).orElseThrow().getMapStatus())
        .isEqualTo(ManagedMapStatus.AVAILABLE);

    store.downloadComplete(listedMap);

    // The status change is posted via SwingUtilities.invokeLater; drain the EDT before asserting.
    SwingUtilities.invokeAndWait(() -> {});

    assertThat(store.getMapByName(LISTED_MAP_NAME).orElseThrow().getMapStatus())
        .isEqualTo(ManagedMapStatus.INSTALLED);
  }
}

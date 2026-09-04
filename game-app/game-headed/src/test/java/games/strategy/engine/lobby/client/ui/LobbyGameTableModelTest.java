package games.strategy.engine.lobby.client.ui;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.google.common.util.concurrent.Runnables;
import games.strategy.net.Node;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import org.jetbrains.annotations.NonNls;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.triplea.http.client.lobby.game.lobby.watcher.LobbyGameListing;
import org.triplea.http.client.web.socket.client.connections.PlayerToLobbyConnection;
import org.triplea.java.Interruptibles;
import org.triplea.lobby.common.GameDescription;
import org.triplea.swing.SwingAction;

@SuppressWarnings("InnerClassMayBeStatic")
@Execution(ExecutionMode.SAME_THREAD)
final class LobbyGameTableModelTest {

  @NonNls private static final String id0 = "id0";
  @NonNls private static final String id1 = "id1";
  private static final GameDescription gameDescription0;
  private static final GameDescription gameDescription1;

  static {
    try {
      gameDescription0 =
          GameDescription.builder()
              .hostedBy(new Node("node", InetAddress.getLocalHost(), 10))
              .startDateTime(Instant.now())
              .status(GameDescription.GameStatus.LAUNCHING)
              .build();

      gameDescription1 =
          GameDescription.builder()
              .hostedBy(new Node("another node", InetAddress.getLocalHost(), 10))
              .startDateTime(Instant.now())
              .status(GameDescription.GameStatus.IN_PROGRESS)
              .build();
    } catch (final UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  @ExtendWith(MockitoExtension.class)
  @Nested
  final class RemoveAndUpdateGameTest {
    private LobbyGameTableModel testObj;
    private LobbyGameListingModel listingModel;
    @Mock private PlayerToLobbyConnection playerToLobbyConnection;

    @BeforeEach
    void setUp() {
      listingModel = new LobbyGameListingModel(playerToLobbyConnection);
      testObj = new LobbyGameTableModel(true, listingModel);
      listingModel
          .getLobbyGameBroadcaster()
          .gameUpdated(
              LobbyGameListing.builder()
                  .gameId(id0)
                  .lobbyGame(gameDescription0.toLobbyGame())
                  .build());
      waitForSwingThreads();
    }

    private void waitForSwingThreads() {
      // add a no-op action to the end of the swing event queue, and then wait for it
      Interruptibles.await(() -> SwingAction.invokeAndWait(Runnables.doNothing()));
    }

    @Test
    void singleGameInModelAfterSetup() {
      assertThat("games are loaded on init", testObj.getRowCount(), is(1));
    }

    @Test
    void updateGame() {
      final int commentColumnIndex = testObj.getColumnIndex(LobbyGameTableModel.Column.Comments);
      assertThat(testObj.getValueAt(0, commentColumnIndex), nullValue());

      listingModel
          .getLobbyGameBroadcaster()
          .gameUpdated(
              LobbyGameListing.builder()
                  .gameId(id0)
                  .lobbyGame(gameDescription1.toLobbyGame())
                  .build());

      waitForSwingThreads();
      assertThat(testObj.getRowCount(), is(1));
      assertThat(testObj.getValueAt(0, commentColumnIndex), is(gameDescription1.getComment()));
    }

    @Test
    void updateGameAddsIfDoesNotExist() {
      listingModel
          .getLobbyGameBroadcaster()
          .gameUpdated(
              LobbyGameListing.builder()
                  .gameId(id1)
                  .lobbyGame(gameDescription1.toLobbyGame())
                  .build());
      waitForSwingThreads();
      assertThat(testObj.getRowCount(), is(2));
    }

    @Test
    void removeGame() {
      listingModel.getLobbyGameBroadcaster().gameRemoved(id0);
      waitForSwingThreads();
      assertThat(testObj.getRowCount(), is(0));
    }

    @Test
    void removeGameThatDoesNotExistIsIgnored() {
      listingModel.getLobbyGameBroadcaster().gameRemoved(id1);
      waitForSwingThreads();
      assertThat(testObj.getRowCount(), is(1));
    }

    @Test
    void statusShowsRealStatusForStartedZeroPlayerGame() throws UnknownHostException {
      final var startedBotGame =
          GameDescription.builder()
              .hostedBy(new Node("bot node", InetAddress.getLocalHost(), 10))
              .startDateTime(Instant.now())
              .status(GameDescription.GameStatus.IN_PROGRESS)
              .playerCount(0)
              .round(1)
              .build();

      listingModel
          .getLobbyGameBroadcaster()
          .gameUpdated(
              LobbyGameListing.builder()
                  .gameId(id0)
                  .lobbyGame(startedBotGame.toLobbyGame())
                  .build());
      waitForSwingThreads();

      final int statusColumnIndex = testObj.getColumnIndex(LobbyGameTableModel.Column.Status);
      assertThat(
          "a round > 0, 0-player game already in progress should show its real status",
          testObj.getValueAt(0, statusColumnIndex),
          is(GameDescription.GameStatus.IN_PROGRESS.toString()));
    }

    @Test
    void statusShowsAvailableForRoundZeroZeroPlayerGame() throws UnknownHostException {
      final var freshlyHostedGame =
          GameDescription.builder()
              .hostedBy(new Node("bot node", InetAddress.getLocalHost(), 10))
              .startDateTime(Instant.now())
              .status(GameDescription.GameStatus.IN_PROGRESS)
              .playerCount(0)
              .round(0)
              .build();

      listingModel
          .getLobbyGameBroadcaster()
          .gameUpdated(
              LobbyGameListing.builder()
                  .gameId(id0)
                  .lobbyGame(freshlyHostedGame.toLobbyGame())
                  .build());
      waitForSwingThreads();

      final int statusColumnIndex = testObj.getColumnIndex(LobbyGameTableModel.Column.Status);
      assertThat(
          "a round 0, 0-player game is still joinable and shows as Available",
          testObj.getValueAt(0, statusColumnIndex),
          is("Available"));
    }
  }

  @Nested
  final class FormatBotStartTimeTest {
    @Test
    void shouldNotThrowException() {
      assertDoesNotThrow(
          () -> LobbyGameTableModel.formatBotStartTime(Instant.now().toEpochMilli()));
    }
  }

  @Nested
  final class StartTimeTest {
    @Test
    void compareToOrdersByEpochMillisChronologically() {
      final var earlier = new LobbyGameTableModel.StartTime(1_000L);
      final var later = new LobbyGameTableModel.StartTime(2_000L);

      // This is the exact bug the Comparable sort key fixes: header-click sorting must follow
      // epoch-millis order, not a lexicographic compare of the formatted time strings (where,
      // e.g., "9:00 AM" sorts after "10:00 AM").
      assertThat(earlier.compareTo(later), lessThan(0));
      assertThat(later.compareTo(earlier), greaterThan(0));
      assertThat(earlier.compareTo(earlier), is(0));
    }

    @Test
    void getColumnClassReturnsStartTimeForStartedColumn() {
      final var listingModel =
          new LobbyGameListingModel(Mockito.mock(PlayerToLobbyConnection.class));
      final var testObj = new LobbyGameTableModel(true, listingModel);

      final int startedColumnIndex = testObj.getColumnIndex(LobbyGameTableModel.Column.Started);

      assertThat(
          "the Started column must expose a Comparable sort key so TableRowSorter invokes"
              + " compareTo instead of falling back to a toString() comparison",
          testObj.getColumnClass(startedColumnIndex),
          is(LobbyGameTableModel.StartTime.class));
    }
  }
}

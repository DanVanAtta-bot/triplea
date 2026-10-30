package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.pro.data.ProPurchaseOption;
import games.strategy.triplea.ai.pro.data.ProPurchaseOptionMap;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.util.ProDanModel;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import org.triplea.java.collections.CollectionUtils;
import org.triplea.java.collections.IntegerMap;

/** Pro AI data. */
@Getter
public final class ProData {
  // Default values
  private boolean isSimulation = false;
  private double winPercentage = 95;
  private double minWinPercentage = 75;
  private @Nullable Territory myCapital = null;
  private List<Territory> myUnitTerritories = new ArrayList<>();
  private Map<Unit, Territory> unitTerritoryMap = new HashMap<>();
  private IntegerMap<UnitType> unitValueMap = new IntegerMap<>();
  private @Nullable ProPurchaseOptionMap purchaseOptions = null;
  // If we purchased units this turn that consume other units, these are the units selected to be
  // consumed. These are already located at the factory locations, so should not be moved. In the
  // future, we could add logic about moving such units to factory territories from elsewhere.
  private final Set<Unit> unitsToBeConsumed = new HashSet<>();
  private double minCostPerHitPoint = Double.MAX_VALUE;

  private AbstractProAi proAi;
  private GameData data;
  private GamePlayer player;

  // The enabled patches as given, each with its scope (see ProAiPatchSpec).
  private List<ProAiPatchSpec> patchSpecs = List.of();
  // The patches on for this player: every unscoped one, plus the scoped ones whose scope matches
  // the player (resolved once the player is known, in initialize).
  private Set<ProAiPatch> patches = Set.of();
  // How often each enabled patch's gate was hit; a patch that never fires cannot change results.
  private final Map<ProAiPatch, Integer> patchHits = new EnumMap<>(ProAiPatch.class);
  // DAN_* gates: each player's strike plan, kept for the game, and this turn's readings.
  private final Map<String, ProDanModel.Plan> danPlans = new HashMap<>();
  @Setter private @Nullable ProDanModel.Turn danTurn = null;

  // FLOW_ALLOCATOR: this turn's direction plan, set at purchase.
  @Setter
  private @Nullable games.strategy.triplea.ai.pro.util.ProFlowAllocator.Plan flowPlan = null;

  // DAN_SUPPLY: hold estimates by territory name, for the current turn.
  private final Map<String, Double> danHoldCache = new HashMap<>();

  public ProData() {
    this(Set.of());
  }

  public ProData(final Collection<ProAiPatch> patches) {
    setPatches(patches);
  }

  /** Patches with scopes, eg: {@code AMPHIB_CAN_OPENER@Axis}; see {@link ProAiPatchSpec}. */
  public static ProData withPatchSpecs(final Collection<ProAiPatchSpec> specs) {
    final ProData proData = new ProData();
    proData.setPatchSpecs(specs);
    return proData;
  }

  /** Swaps the enabled patches, eg: to scope a variant to a single turn. */
  public void setPatches(final Collection<ProAiPatch> patches) {
    setPatchSpecs(patches.stream().map(ProAiPatchSpec::unscoped).toList());
  }

  /** Swaps the enabled patches and their scopes. */
  public void setPatchSpecs(final Collection<ProAiPatchSpec> specs) {
    this.patchSpecs = List.copyOf(specs);
    resolvePatches();
  }

  private void resolvePatches() {
    final Collection<String> alliances =
        player == null || data == null
            ? List.of()
            : data.getAllianceTracker().getAlliancesPlayerIsIn(player);
    final Set<ProAiPatch> on = EnumSet.noneOf(ProAiPatch.class);
    for (final ProAiPatchSpec spec : patchSpecs) {
      if (!spec.isScoped() || (player != null && spec.appliesTo(player.getName(), alliances))) {
        on.add(spec.patch());
      }
    }
    patches = Set.copyOf(on);
  }

  /** Like {@link #isPatchOn} but doesn't count a hit; for deciding whether to compute a gate. */
  public boolean hasPatch(final ProAiPatch patch) {
    return patches.contains(patch);
  }

  public boolean isPatchOn(final ProAiPatch patch) {
    if (!patches.contains(patch)) {
      return false;
    }
    patchHits.merge(patch, 1, Integer::sum);
    return true;
  }

  /** Counts a hit for a patch that changed a decision. */
  public void countPatchHit(final ProAiPatch patch) {
    patchHits.merge(patch, 1, Integer::sum);
  }

  public Map<ProAiPatch, Integer> getPatchHits() {
    return Map.copyOf(patchHits);
  }

  /**
   * Patch hits keyed by spec label: a scoped patch reads {@code NAME@Scope}, others {@code NAME}.
   */
  public Map<String, Integer> getPatchHitsByLabel() {
    final Map<String, Integer> byLabel = new TreeMap<>();
    patchHits.forEach(
        (patch, hits) ->
            byLabel.put(
                patchSpecs.stream()
                    .filter(spec -> spec.patch() == patch)
                    .map(ProAiPatchSpec::label)
                    .findFirst()
                    .orElse(patch.name()),
                hits));
    return byLabel;
  }

  public void initialize(final AbstractProAi proAi) {
    hiddenInitialize(proAi, proAi.getGameData(), proAi.getGamePlayer(), false);
  }

  public void initializeSimulation(
      final AbstractProAi proAi, final GameData data, final GamePlayer player) {
    hiddenInitialize(proAi, data, player, true);
  }

  public Territory getUnitTerritory(final Unit unit) {
    return unitTerritoryMap.get(unit);
  }

  public int getUnitValue(final UnitType type) {
    return unitValueMap.getInt(type);
  }

  private void hiddenInitialize(
      final AbstractProAi proAi,
      final GameData data,
      final GamePlayer player,
      final boolean isSimulation) {
    this.proAi = proAi;
    this.data = data;
    this.player = player;
    this.isSimulation = isSimulation;
    resolvePatches();

    if (!Properties.getLowLuck(data.getProperties())) {
      winPercentage = 90;
      minWinPercentage = 65;
    }
    myCapital =
        TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data.getMap())
            .orElse(null);
    myUnitTerritories =
        CollectionUtils.getMatches(
            data.getMap().getTerritories(), Matches.territoryHasUnitsOwnedBy(player));
    unitTerritoryMap = newUnitTerritoryMap(data);
    unitValueMap = new TuvCostsCalculator().getCostsForTuv(player);
    purchaseOptions = new ProPurchaseOptionMap(player, data);
    minCostPerHitPoint = getMinCostPerHitPoint(purchaseOptions.getLandOptions());
  }

  private static Map<Unit, Territory> newUnitTerritoryMap(final GameState data) {
    final Map<Unit, Territory> unitTerritoryMap = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnits()) {
        unitTerritoryMap.put(u, t);
      }
    }
    return unitTerritoryMap;
  }

  private double getMinCostPerHitPoint(final List<ProPurchaseOption> landPurchaseOptions) {
    double minCostPerHitPoint = Double.MAX_VALUE;
    for (final ProPurchaseOption ppo : landPurchaseOptions) {
      if (ppo.getCostPerHitPoint() < minCostPerHitPoint) {
        minCostPerHitPoint = ppo.getCostPerHitPoint();
      }
    }
    return minCostPerHitPoint;
  }

  public ProTerritory getProTerritory(Map<Territory, ProTerritory> moveMap, Territory t) {
    return moveMap.computeIfAbsent(t, k -> new ProTerritory(t, this));
  }
}

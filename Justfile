set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

# Show this help message
help:
    @just --list

# Install pre-commit hooks
setup:
    pre-commit install --hook-type pre-push

# Apply code formatting
format:
    ./gradlew spotlessApply

# Champion 8: the patches Hard (AI) seats play with
patches := "PURCHASE_SIM_NO_PHANTOM,PURCHASE_SIM_CONQUERED,BUY_DEFENSIVE_LAND_MIX,BUY_LESS_IDLE_NAVY,BUY_TRANSPORTS_ON_DEMAND,DAN_COOKED,NCM_DEFENSIVE_STANCE_FIX,AMPHIB_READD_DROPPED,AMPHIB_CAN_OPENER@Axis,AMPHIB_CAN_OPENER_VALUABLE@Axis,BID_PLACE_FOR_R1,PURCHASE_CARRIER_DECK_FIX,SEA_BLOCKER,SBR_WHEN_FREE,STRAFE_CANT_HOLD,PURCHASE_HELD_UNITS,NO_VALUE"

# Launch the headed TripleA game client, Hard (AI) as the champion
run:
    JAVA_TOOL_OPTIONS='-Dtriplea.ai.patches={{patches}}' ./gradlew :game-headed:run

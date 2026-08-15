package com.kadr.app.data.local

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun stateToString(state: AssetState): String = state.name

    /**
     * An unknown name means the row was written by a newer build. Treat it as
     * undiscovered rather than crashing — the scanner will sort it out.
     */
    @TypeConverter
    fun stringToState(value: String): AssetState =
        runCatching { AssetState.valueOf(value) }.getOrDefault(AssetState.DISCOVERED)
}

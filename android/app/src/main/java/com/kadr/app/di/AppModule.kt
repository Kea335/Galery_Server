package com.kadr.app.di

import android.content.Context
import androidx.media3.datasource.cache.SimpleCache
import androidx.room.Room
import com.kadr.app.data.local.GalleryDao
import com.kadr.app.data.local.KadrDatabase
import com.kadr.app.data.local.LocalAssetDao
import com.kadr.app.data.video.VideoCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KadrDatabase =
        Room.databaseBuilder(context, KadrDatabase::class.java, "kadr.db")
            .addMigrations(KadrDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideAssetDao(database: KadrDatabase): LocalAssetDao = database.assets()

    @Provides
    fun provideGalleryDao(database: KadrDatabase): GalleryDao = database.gallery()

    /** The app's one media cache; tests open their own folder instead. */
    @Provides
    @Singleton
    fun provideMediaCache(videoCache: VideoCache): SimpleCache = videoCache.cache

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // The server may grow fields before the app knows about them.
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}

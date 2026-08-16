package com.kadr.app.data.remote

import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** The §9 contract, verbatim. */
interface KadrApi {

    @GET("api/v1/health")
    suspend fun health(): Envelope<HealthResponse>

    /** Anyone who signs in sees the same library (§16 decision). */
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): Envelope<LoginResponse>

    @POST("api/v1/auth/revoke")
    suspend fun revoke(): Envelope<Map<String, Boolean>>

    @GET("api/v1/auth/devices")
    suspend fun devices(): Envelope<DeviceListResponse>

    /** Delta sync (§9). Ordered by updated_at; includes tombstones. */
    @GET("api/v1/assets")
    suspend fun assets(
        @Query("since") since: Long,
        @Query("limit") limit: Int = 500,
    ): Envelope<AssetListResponse>

    @GET("api/v1/assets/trash")
    suspend fun trash(@Query("limit") limit: Int = 200): Envelope<TrashListResponse>

    @DELETE("api/v1/assets/{id}")
    suspend fun deleteAsset(@Path("id") id: String): Envelope<Map<String, String>>

    @POST("api/v1/assets/{id}/restore")
    suspend fun restoreAsset(@Path("id") id: String): Envelope<RemoteAssetDto>

    /** Always call this before uploading (§10.3). Max 500 hashes. */
    @POST("api/v1/assets/check")
    suspend fun check(@Body body: CheckRequest): Envelope<CheckResponse>

    @POST("api/v1/uploads")
    suspend fun createUpload(@Body body: CreateUploadRequest): Envelope<CreateUploadResponse>

    @PATCH("api/v1/uploads/{id}")
    suspend fun uploadChunk(
        @Path("id") uploadId: String,
        @Header("Content-Range") contentRange: String,
        @Body chunk: RequestBody,
    ): Envelope<ChunkResponse>

    /** Where did we leave off? Called after a crash or reboot (§10.4). */
    @GET("api/v1/uploads/{id}")
    suspend fun uploadStatus(@Path("id") uploadId: String): Envelope<UploadStatusResponse>

    @POST("api/v1/uploads/{id}/complete")
    suspend fun completeUpload(@Path("id") uploadId: String): Envelope<CompleteResponse>
}

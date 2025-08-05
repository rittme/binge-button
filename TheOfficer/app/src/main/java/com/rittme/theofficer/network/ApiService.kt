package com.rittme.theofficer.network

import com.rittme.theofficer.data.PlaybackStateUpdateRequest
import com.rittme.theofficer.data.ShowInfoResponse
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.Interceptor

interface ApiService {
    // Adjust base URL to your server's IP and port
    // For emulator, if server is on host machine: "http://10.0.2.2:8080/"
    // For physical device on same network: "http://YOUR_SERVER_IP:8080/"
    companion object {
        private const val BASE_URL = "http://10.0.2.2:8080/" // Backend server running on port 8080
        private const val API_KEY = "your-secret-token" // Should match the backend API key

        fun create(): ApiService {
            val logger = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
            
            // Add API key authentication interceptor
            val authInterceptor = Interceptor { chain ->
                val originalRequest = chain.request()
                val newRequest = originalRequest.newBuilder()
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .build()
                chain.proceed(newRequest)
            }
            
            val client = OkHttpClient.Builder()
                .addInterceptor(logger)
                .addInterceptor(authInterceptor)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }

    @GET("api/show/info")
    suspend fun getShowInfo(): Response<ShowInfoResponse>

    @POST("api/show/state")
    suspend fun updatePlaybackState(@Body state: PlaybackStateUpdateRequest): Response<Unit> // Void response
}

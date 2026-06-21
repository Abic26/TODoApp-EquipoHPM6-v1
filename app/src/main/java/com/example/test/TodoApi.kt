package com.example.test

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

interface TodoApi {
    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<ApiResponse<AuthData>>

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<ApiResponse<AuthData>>

    @GET("api/tasks")
    suspend fun getTasks(@Header("Authorization") token: String): Response<ApiResponse<TaskListData>>

    @POST("api/tasks")
    suspend fun createTask(@Header("Authorization") token: String, @Body task: CreateTaskRequest): Response<ApiResponse<TodoTaskDto>>

    @GET("api/tasks/{id}")
    suspend fun getTask(@Header("Authorization") token: String, @Path("id") id: String): Response<ApiResponse<TodoTaskDto>>

    @PUT("api/tasks/{id}")
    suspend fun updateTask(@Header("Authorization") token: String, @Path("id") id: String, @Body task: UpdateTaskRequest): Response<ApiResponse<TodoTaskDto>>

    @DELETE("api/tasks/{id}")
    suspend fun deleteTask(@Header("Authorization") token: String, @Path("id") id: String): Response<ApiResponse<Unit>>

    @PATCH("api/tasks/{id}/complete")
    suspend fun toggleComplete(@Header("Authorization") token: String, @Path("id") id: String): Response<ApiResponse<TodoTaskDto>>

    companion object {
        private const val BASE_URL = "https://todoapp-backend-delta.vercel.app/"
        
        fun create(): TodoApi {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TodoApi::class.java)
        }
    }
}

data class ApiResponse<T>(
    val success: Boolean,
    val message: String,
    val data: T?
)

data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    val phone: String? = null
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class AuthData(
    val token: String,
    val user: UserDto
)

data class UserDto(
    val id: String,
    val name: String,
    val email: String,
    val phone: String?,
    val createdAt: String
)

data class TaskListData(
    val tasks: List<TodoTaskDto>,
    val pagination: PaginationDto
)

data class PaginationDto(
    val page: Int,
    val limit: Int,
    val total: Int,
    val pages: Int
)

data class CreateTaskRequest(
    val title: String,
    val description: String? = null,
    val category: String,
    val priority: String,
    val status: String = "PENDING",
    val reminderDateTime: String? = null,
    val source: String = "APP",
    val whatsappNumber: String? = null
)

data class UpdateTaskRequest(
    val title: String? = null,
    val description: String? = null,
    val category: String? = null,
    val priority: String? = null,
    val status: String? = null,
    val reminderDateTime: String? = null
)

data class TodoTaskDto(
    val id: String,
    val title: String,
    val description: String?,
    val category: String,
    val priority: String,
    val status: String,
    val reminderDateTime: String?,
    val source: String,
    val whatsappNumber: String?,
    val createdAt: String,
    val updatedAt: String
)

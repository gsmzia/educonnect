package com.security.myapplication.network

import com.security.myapplication.models.*
import com.security.myapplication.posts.Post
import com.security.myapplication.posts.PostComment
import com.security.myapplication.posts.PostCommentPageResponse
import com.security.myapplication.posts.PostFeedResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

interface ApiService {

    // â”€â”€ Auth â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @POST("request-otp")
    suspend fun requestOtp(@Body request: OTPRequest): MessageResponse

    @POST("signup")
    suspend fun signup(
        @Query("otp_code") otpCode: String? = null,
        @Body request: SignupRequest
    ): LoginResponse

    @POST("login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    /** Server-authoritative simple/academic space selection for this account. */
    @POST("session-mode")
    suspend fun updateSessionMode(@Body request: SessionModeRequest): SessionModeResponse

    // â”€â”€ User â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @GET("users/{user_id}")
    suspend fun getUser(@Path("user_id") userId: Int): User

    @GET("users/by-username/{username}")
    suspend fun getUserByUsername(@Path("username") username: String): User

    // â”€â”€ Programs (Teacher) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    // â”€â”€ Teacher Group Creation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @POST("groups/create")
    suspend fun createGroup(@Body request: GroupCreateRequest): GroupResponse

    // â”€â”€ Groups â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @GET("users/{user_id}/groups")
    suspend fun getUserGroups(@Path("user_id") userId: Int): List<Group>

    // â”€â”€ Forgot Password â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @POST("forgot-password/request-otp")
    suspend fun forgotPasswordRequestOtp(@Body request: ForgotPasswordRequest): MessageResponse

    @POST("forgot-password/reset")
    suspend fun forgotPasswordReset(@Body request: ForgotPasswordReset): MessageResponse


    // â”€â”€ Chat â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @POST("groups/{group_id}/chat")
    suspend fun sendMessage(
        @Path("group_id") groupId: Int,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
        @Body msg: ChatRequest
    ): ChatMessage

    @Multipart
    @POST("groups/{group_id}/chat/upload")
    suspend fun sendGroupMedia(
        @Path("group_id") groupId: Int,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Part("sender_id") senderId: RequestBody,
        @Part("text") text: RequestBody,
        @Part("message_type") messageType: RequestBody,
        @Part("duration_sec") durationSec: RequestBody? = null,
        @Part file: MultipartBody.Part
    ): ChatMessage

    /**
     * Upload one acknowledged chunk of a personal or group media message. The
     * server persists the byte offset before replying, so a paused transfer can
     * continue from its stored Room progress instead of uploading from zero.
     */
    @Multipart
    @POST("transfers/resumable-upload/chunk")
    suspend fun uploadResumableMediaChunk(
        @Part("transfer_id") transferId: RequestBody,
        @Part("chat_type") chatType: RequestBody,
        @Part("sender_id") senderId: RequestBody,
        @Part("peer_id") peerId: RequestBody,
        @Part("text") text: RequestBody,
        @Part("message_type") messageType: RequestBody,
        @Part("duration_sec") durationSec: RequestBody,
        @Part("file_name") fileName: RequestBody,
        @Part("total_bytes") totalBytes: RequestBody,
        @Part("offset") offset: RequestBody,
        @Part("is_final") isFinal: RequestBody,
        @Part chunk: MultipartBody.Part
    ): ResumableUploadChunkResponse

    @GET("groups/{group_id}/chat")
    suspend fun getMessages(
        @Path("group_id") groupId: Int,
        @Query("before_id") beforeId: Int? = null,
        @Query("after_id") afterId: Int? = null,
        @Query("limit") limit: Int = 50
    ): List<ChatMessage>

    @POST("groups/{group_id}/chat/{message_id}/delete_everyone")
    suspend fun deleteGroupMessageForEveryone(
        @Path("group_id") groupId: Int,
        @Path("message_id") messageId: Int,
        @Body req: DeleteEveryoneRequest
    ): MessageResponse

    @POST("groups/{group_id}/chat/{message_id}/pin")
    suspend fun pinGroupMessage(
        @Path("group_id") groupId: Int,
        @Path("message_id") messageId: Int
    ): MessageResponse

    @POST("groups/{group_id}/chat/{message_id}/unpin")
    suspend fun unpinGroupMessage(
        @Path("group_id") groupId: Int,
        @Path("message_id") messageId: Int
    ): MessageResponse

    // â”€â”€ Assignments â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Multipart
    @POST("assignments")
    suspend fun uploadAssignment(
        @Part("group_id") groupId: RequestBody,
        @Part("title") title: RequestBody,
        @Part("description") description: RequestBody,
        @Part file: MultipartBody.Part
    ): Assignment

    @GET("groups/{group_id}/assignments")
    suspend fun getAssignments(@Path("group_id") groupId: Int): List<Assignment>

    // â”€â”€ Attendance â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @POST("attendance")
    suspend fun markAttendance(@Body req: AttendanceRequest): Attendance

    @GET("groups/{group_id}/attendance")
    suspend fun getAttendance(
        @Path("group_id") groupId: Int,
        @Query("date") date: String
    ): List<Attendance>

    @GET("groups/{group_id}/students")
    suspend fun getGroupStudents(@Path("group_id") groupId: Int): List<User>

    @GET("groups/{group_id}/members")
    suspend fun getGroupMembers(@Path("group_id") groupId: Int): List<User>

    @POST("groups/{group_id}/members/remove")
    suspend fun removeGroupMember(
        @Path("group_id") groupId: Int,
        @Body req: RemoveGroupMemberRequest
    ): MessageResponse

    @POST("groups/{group_id}/members")
    suspend fun addGroupMembers(@Path("group_id") groupId: Int, @Body req: AddGroupMembersRequest): MessageResponse

    @POST("groups/{group_id}/leave")
    suspend fun leaveGroup(@Path("group_id") groupId: Int, @Body req: LeaveGroupRequest): MessageResponse

    @GET("groups/{group_id}/membership/{user_id}")
    suspend fun getGroupMembership(@Path("group_id") groupId: Int, @Path("user_id") userId: Int): GroupMembershipResponse

    @GET("groups/{group_id}")
    suspend fun getGroupDetails(@Path("group_id") groupId: Int): GroupDetailsResponse

    @PATCH("groups/{group_id}/profile")
    suspend fun updateGroupProfile(@Path("group_id") groupId: Int, @Body req: GroupProfileUpdateRequest): GroupProfileUpdateResponse

    // â”€â”€ Program Ownership & Personal Chats â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @GET("personal_chats/{user_id}")
    suspend fun getPersonalChats(@Path("user_id") userId: Int): List<PersonalChatUser>

    @GET("personal_blocks/{user_id}/{other_user_id}")
    suspend fun getPersonalBlockStatus(
        @Path("user_id") userId: Int,
        @Path("other_user_id") otherUserId: Int
    ): PersonalBlockStatus

    @POST("personal_blocks/{blocker_id}/{blocked_user_id}")
    suspend fun blockPersonalUser(
        @Path("blocker_id") blockerId: Int,
        @Path("blocked_user_id") blockedUserId: Int
    ): PersonalBlockStatus

    @DELETE("personal_blocks/{blocker_id}/{blocked_user_id}")
    suspend fun unblockPersonalUser(
        @Path("blocker_id") blockerId: Int,
        @Path("blocked_user_id") blockedUserId: Int
    ): PersonalBlockStatus

    @GET("personal_messages/{user_id}/{other_user_id}")
    suspend fun getPersonalMessages(
        @Path("user_id") userId: Int,
        @Path("other_user_id") otherUserId: Int,
        @Query("before_id") beforeId: Int? = null,
        @Query("after_id") afterId: Int? = null,
        @Query("limit") limit: Int = 50
    ): List<PersonalMessage>

    @POST("personal_messages/{message_id}/media-consumed")
    suspend fun confirmPersonalMediaConsumed(
        @Path("message_id") messageId: Int
    ): MessageResponse

    @POST("personal_messages/{other_user_id}/clear")
    suspend fun clearPersonalChat(
        @Path("other_user_id") otherUserId: Int
    ): MessageResponse

    @POST("personal_messages")
    suspend fun sendPersonalMessage(
        @Header("Idempotency-Key") idempotencyKey: String? = null,
        @Body request: PersonalMessageCreateRequest
    ): PersonalMessage

    @Multipart
    @POST("personal_messages/upload")
    suspend fun sendPersonalMedia(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Part("sender_id") senderId: RequestBody,
        @Part("receiver_id") receiverId: RequestBody,
        @Part("text") text: RequestBody,
        @Part("message_type") messageType: RequestBody,
        @Part("duration_sec") durationSec: RequestBody? = null,
        @Part file: MultipartBody.Part
    ): PersonalMessage

    @POST("personal_messages/forward")
    suspend fun forwardPersonalMessage(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body req: ForwardPersonalRequest
    ): PersonalMessage

    @POST("groups/{group_id}/chat/forward")
    suspend fun forwardGroupMessage(
        @Path("group_id") groupId: Int,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body req: ForwardGroupRequest
    ): ChatMessage

    @POST("groups/{group_id}/chat/clear")
    suspend fun clearGroupChat(
        @Path("group_id") groupId: Int
    ): MessageResponse

    @POST("personal_messages/{message_id}/delete_everyone")
    suspend fun deletePersonalMessageForEveryone(
        @Path("message_id") messageId: Int,
        @Body req: DeleteEveryoneRequest
    ): MessageResponse

    @POST("personal_messages/{message_id}/pin")
    suspend fun pinPersonalMessage(
        @Path("message_id") messageId: Int
    ): MessageResponse

    @POST("personal_messages/{message_id}/unpin")
    suspend fun unpinPersonalMessage(
        @Path("message_id") messageId: Int
    ): MessageResponse

    @POST("approval_requests/{request_id}/respond")
    suspend fun respondApprovalRequest(
        @Path("request_id") requestId: Int,
        @Body request: ApprovalRespondRequest
    ): MessageResponse

    @PATCH("users/{user_id}/profile")
    suspend fun updateUserProfile(
        @Path("user_id") userId: Int,
        @Body request: UserProfileUpdateRequest
    ): User

    @GET("home_feed/{user_id}")
    suspend fun getHomeFeed(@Path("user_id") userId: Int): List<HomeFeedItem>

    // â”€â”€ Mark as Read â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @POST("groups/{group_id}/chat/read")
    suspend fun markGroupChatRead(
        @Path("group_id") groupId: Int,
        @Query("user_id") userId: Int
    ): MessageResponse

    @POST("personal_messages/{user_id}/{other_user_id}/read")
    suspend fun markPersonalChatRead(
        @Path("user_id") userId: Int,
        @Path("other_user_id") otherUserId: Int
    ): MessageResponse

    // â”€â”€ Online Presence â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @POST("users/{user_id}/online")
    suspend fun pingOnline(@Path("user_id") userId: Int): MessageResponse

    @GET("users/{user_id}/online")
    suspend fun checkOnline(@Path("user_id") userId: Int): OnlineStatusResponse

    // â”€â”€ FCM Push Notifications â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /**
     * Register or refresh the device FCM token on the backend.
     * Called on every token rotation (onNewToken) and at login.
     * Backend should store (userId â†’ token) so it can send targeted pushes.
     *
     * Endpoint convention: POST users/{user_id}/fcm-token
     * Body: { "token": "<fcm_registration_token>", "platform": "android" }
     */
    @POST("users/{user_id}/fcm-token")
    suspend fun updateFcmToken(
        @Path("user_id") userId: Int,
        @Body request: FcmTokenRequest
    ): MessageResponse

    @DELETE("users/{user_id}/fcm-token")
    suspend fun removeFcmToken(
        @Path("user_id") userId: Int,
        @Query("token") token: String
    ): MessageResponse

    // â”€â”€ Posts / Announcements â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @GET("posts")
    suspend fun getPosts(
        @Query("user_id") userId: Int? = null,
        @Query("tab") tab: Int? = 0,
        @Query("role") role: String? = null,
        @Query("limit") limit: Int = 20,
        @Query("before_timestamp") beforeTimestamp: String? = null,
        @Query("before_id") beforeId: Int? = null
    ): PostFeedResponse

    @POST("posts")
    suspend fun createPost(
        @Header("Idempotency-Key") idempotencyKey: String? = null,
        @Body request: CreatePostNetworkRequest
    ): Post

    @Multipart
    @POST("posts/upload")
    suspend fun createPostWithMedia(
        @Header("Idempotency-Key") idempotencyKey: String? = null,
        @Part("author_id") authorId: RequestBody,
        @Part("text") text: RequestBody,
        @Part("media_type") mediaType: RequestBody,
        @Part file: MultipartBody.Part,
        @Part("target_audience") targetAudience: RequestBody? = null
    ): Post

    @Multipart
    @POST("posts/media/upload")
    suspend fun uploadPostMedia(
        @Part file: MultipartBody.Part
    ): MediaUploadResponse

    /** One acknowledged post-media chunk; retry with the returned offset. */
    @Multipart
    @POST("posts/media/resumable-upload/chunk")
    suspend fun uploadResumablePostMediaChunk(
        @Part("transfer_id") transferId: RequestBody,
        @Part("author_id") authorId: RequestBody,
        @Part("media_type") mediaType: RequestBody,
        @Part("file_name") fileName: RequestBody,
        @Part("total_bytes") totalBytes: RequestBody,
        @Part("offset") offset: RequestBody,
        @Part("is_final") isFinal: RequestBody,
        @Part chunk: MultipartBody.Part
    ): ResumableUploadChunkResponse


    @DELETE("posts/{post_id}")
    suspend fun deletePost(
        @Path("post_id") postId: Int,
        @Query("user_id") userId: Int
    ): MessageResponse

    @POST("posts/{post_id}/like")
    suspend fun togglePostLike(
        @Path("post_id") postId: Int,
        @Body request: LikeToggleRequest
    ): LikeToggleResponse

    @POST("posts/{post_id}/save")
    suspend fun togglePostSave(
        @Path("post_id") postId: Int,
        @Body request: SaveToggleRequest
    ): SaveToggleResponse

    @GET("posts/{post_id}/comments")
    suspend fun getPostComments(
        @Path("post_id") postId: Int,
        @Query("user_id") userId: Int? = null,
        @Query("limit") limit: Int = 20,
        @Query("before_timestamp") beforeTimestamp: String? = null,
        @Query("before_id") beforeId: Int? = null
    ): PostCommentPageResponse

    @POST("posts/{post_id}/comments")
    suspend fun addPostComment(
        @Path("post_id") postId: Int,
        @Body request: CommentCreateRequest
    ): PostComment

    @POST("posts/comments/{comment_id}/like")
    suspend fun toggleCommentLike(
        @Path("comment_id") commentId: Int,
        @Body request: LikeToggleRequest
    ): LikeToggleResponse

    @POST("posts/{post_id}/poll/vote")
    suspend fun votePostPoll(
        @Path("post_id") postId: Int,
        @Body request: PollVoteRequest
    ): Post

    // â”€â”€ Multi-Tenant Academic Hierarchy & Auto-Enrolment â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @POST("api/academic/verify")
    suspend fun verifyUniversity(
        @Body request: com.security.myapplication.academic.UniversityVerifyRequest
    ): com.security.myapplication.academic.UniversityResponse

    @GET("api/academic/validate-key/{key}")
    suspend fun validateUniversityKey(
        @Path("key") key: String
    ): com.security.myapplication.academic.ValidateKeyResponse

    @POST("api/academic/curriculum/setup")
    suspend fun setupCurriculum(
        @Body request: com.security.myapplication.academic.CurriculumSetupRequest
    ): com.security.myapplication.academic.SetupCurriculumResponse

    @GET("api/academic/curriculum/{uni_key}")
    suspend fun getCurriculumTree(
        @Path("uni_key") uniKey: String
    ): com.security.myapplication.academic.CurriculumResponse

    @POST("api/academic/teacher/assign-subjects")
    suspend fun teacherAssignSubjects(
        @Body request: com.security.myapplication.academic.TeacherSubjectAssignmentRequest
    ): com.security.myapplication.academic.TeacherAssignmentResponse

    @POST("api/academic/student/auto-enroll")
    suspend fun studentAutoEnroll(
        @Body request: com.security.myapplication.academic.StudentAutoEnrollRequest
    ): com.security.myapplication.academic.StudentAutoEnrollResponse

    // -- Founder Email OTP Verification
    @POST("api/academic/founder/request-email-otp")
    suspend fun founderRequestEmailOtp(
        @Body request: com.security.myapplication.academic.FounderOTPRequest
    ): com.security.myapplication.academic.FounderOTPResponse

    @POST("api/academic/founder/verify-email-otp")
    suspend fun founderVerifyEmailOtp(
        @Body request: com.security.myapplication.academic.FounderVerifyOTPRequest
    ): com.security.myapplication.academic.FounderVerifyOTPResponse

    @GET("api/academic/founder/status/{university_id}")
    suspend fun getUniversityStatus(
        @Path("university_id") universityId: Int
    ): com.security.myapplication.academic.UniversityStatusResponse

    // ── Incremental Curriculum & Teacher Claiming ─────────────────────────────
    @POST("api/academic/curriculum/add-subject")
    suspend fun addNewSubject(
        @Body request: com.security.myapplication.academic.AddNewSubjectRequest
    ): com.security.myapplication.academic.AddNewSubjectResponse

    @GET("api/academic/teacher/unclaimed-subjects/{uni_key}")
    suspend fun getUnclaimedSubjects(
        @Path("uni_key") uniKey: String
    ): com.security.myapplication.academic.UnclaimedSubjectsListResponse

    @POST("api/academic/teacher/claim-subject")
    suspend fun claimSubject(
        @Body request: com.security.myapplication.academic.ClaimSubjectRequest
    ): com.security.myapplication.academic.ClaimSubjectResponse
}

/**
 * Global Session & Auth State Manager.
 */
object AuthManager {
    @Volatile
    var authToken: String? = null

    @Volatile
    var onUnauthorizedCallback: (() -> Unit)? = null

    // Several requests can fail together when a token expires.  Dispatch one
    // expiry event only; otherwise each OkHttp callback can clear/navigate again.
    private val expiryDispatched = AtomicBoolean(false)

    fun setToken(token: String?) {
        authToken = token
        if (!token.isNullOrBlank()) expiryDispatched.set(false)
    }

    fun clear() {
        authToken = null
    }

    fun notifyUnauthorized() {
        if (expiryDispatched.compareAndSet(false, true)) {
            onUnauthorizedCallback?.invoke()
        }
    }
}

object ApiClient {
    /**
     * URL is injected at build time via BuildConfig.BASE_URL:
     *   debug build   â†’ http://10.119.186.145:8000/  (local WiFi, see app/build.gradle.kts)
     *   release build â†’ https://api.yourapp.com/      (real HTTPS backend)
     *
     * To update the dev IP: change buildConfigField("BASE_URL", ...) in build.gradle.kts
     * and run Rebuild Project. Never hardcode IPs here.
     */
    val BASE_URL: String = com.security.myapplication.BuildConfig.BASE_URL

    private val authInterceptor = okhttp3.Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
            .header("Accept", "application/json")

        val token = AuthManager.authToken
        if (!token.isNullOrBlank() && !original.headers.names().contains("Authorization")) {
            val formatted = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
            builder.header("Authorization", formatted)
        }

        val response = chain.proceed(builder.build())
        // 403 is a valid authorization outcome (for example, an academic
        // endpoint while the account is intentionally in Simple mode). Only a
        // 401 means the credential itself is absent/expired and warrants a
        // forced session-expiry flow.
        if (response.code == 401) {
            AuthManager.notifyUnauthorized()
        }
        response
    }

    private val dynamicTimeoutInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val isMultipart = request.body is okhttp3.MultipartBody || 
                          request.header("Content-Type")?.contains("multipart", ignoreCase = true) == true

        if (isMultipart) {
            // Heavy media uploads (videos/images/large files) get 300-second (5 min) timeouts for slow 3G/4G connections
            chain.withConnectTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                .withReadTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .withWriteTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .proceed(request)
        } else {
            // Lightweight JSON actions should fail fast; durable retry/reconcile
            // paths can recover, whereas making a user wait a minute feels hung.
            chain.withConnectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .withReadTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .withWriteTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .proceed(request)
        }
    }

    private val okHttpClient: okhttp3.OkHttpClient by lazy {
        val builder = okhttp3.OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(dynamicTimeoutInterceptor)
            // NOTE: Idempotency-Key is set per-request at the ApiService call site (sendPersonalMedia /
            // sendGroupMedia). The key is stable across retries, preventing duplicate videos/messages.
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            // Enable retry on connection failure to handle cold-start reconnects
            .retryOnConnectionFailure(true)

        // Transport security is enforced by the manifest/network-security config:
        // all shipped endpoints must use HTTPS. Do not add a placeholder certificate
        // pin here: an unverified pin would turn a valid certificate renewal into an
        // app-wide outage. Add current and backup production SPKI pins only after
        // they are supplied and verified by the API certificate owner.

        builder.build()
    }

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Production Sealed Class Wrapper for Safe, Type-Safe API Calls
// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

sealed interface ApiResult<out T> {
    data class Success<out T>(val data: T) : ApiResult<T>
    data class HttpError(val code: Int, val message: String?, val errorBody: String? = null) : ApiResult<Nothing>
    data class NetworkError(val exception: Throwable) : ApiResult<Nothing>
    data class UnknownError(val throwable: Throwable) : ApiResult<Nothing>

    val isSuccess: Boolean get() = this is Success
    fun getOrNull(): T? = (this as? Success)?.data
}

suspend fun <T> safeApiCall(apiCall: suspend () -> T): ApiResult<T> {
    return try {
        ApiResult.Success(apiCall())
    } catch (e: retrofit2.HttpException) {
        val errorBody = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
        ApiResult.HttpError(e.code(), e.message(), errorBody)
    } catch (e: java.io.IOException) {
        ApiResult.NetworkError(e)
    } catch (e: Throwable) {
        ApiResult.UnknownError(e)
    }
}

// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Custom Counting Request Body for Live Upload Progress Tracking
// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

class CountingRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
) : RequestBody() {

    override fun contentType(): okhttp3.MediaType? = delegate.contentType()

    override fun contentLength(): Long = try { delegate.contentLength() } catch (_: Exception) { -1L }

    override fun writeTo(sink: BufferedSink) {
        val countingSink = object : ForwardingSink(sink) {
            private var bytesWritten = 0L
            private val total = contentLength()

            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                onProgress(bytesWritten, total)
            }
        }
        val bufferedSink = countingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }
}

/**
 * Robust platform-wide user lookup helper:
 * 1. Tries GET /users/by-username/{username} first
 * 2. If endpoint is unavailable (e.g. older cloud deployment), scans /users/{id}
 *    matching username and display name case-insensitively with friendly fallback.
 */
suspend fun lookupUserRobust(queryRaw: String, currentUserId: Int): User? {
    val clean = queryRaw.trim().removePrefix("@").trim()
    if (clean.isBlank()) return null

    // 1. Direct API lookup
    try {
        val user = ApiClient.apiService.getUserByUsername(clean)
        return user
    } catch (_: Exception) {}

    // 2. Cloud Server /users/{id} fast parallel batched probe (IDs 1 to 50 in batches of 10)
    val batches = (1..50).chunked(10)
    for (batch in batches) {
        val match = coroutineScope {
            val deferreds = batch.map { id ->
                async(Dispatchers.IO) {
                    try {
                        val u = ApiClient.apiService.getUser(id)
                        val uname = u.username?.trim() ?: ""
                        val name = u.name.trim()
                        if (uname.equals(clean, ignoreCase = true) ||
                            clean.equals(uname, ignoreCase = true) ||
                            uname.contains(clean, ignoreCase = true) ||
                            clean.contains(uname, ignoreCase = true) ||
                            name.equals(clean, ignoreCase = true) ||
                            name.contains(clean, ignoreCase = true)
                        ) {
                            u
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            deferreds.awaitAll().firstOrNull { it != null }
        }
        if (match != null) return match
    }
    return null
}

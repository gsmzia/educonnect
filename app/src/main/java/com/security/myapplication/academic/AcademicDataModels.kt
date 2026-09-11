package com.security.myapplication.academic

// ── University Verification & Key Models ──────────────────────────────────────

data class UniversityVerifyRequest(
    val name: String,
    val country: String
)

data class UniversityResponse(
    val id: Int,
    val name: String,
    val country: String,
    val uni_key: String,
    val founder_id: Int?,
    val created_at: String
)

data class ValidateKeyResponse(
    val valid: Boolean,
    val university_id: Int? = null,
    val name: String? = null,
    val country: String? = null,
    val uni_key: String? = null,
    val message: String? = null
)

// ── Curriculum Setup Models (Founder Wizard Input) ────────────────────────────

data class SubjectInput(
    val name: String,
    val code: String? = null
)

data class SemesterInput(
    val semester_num: Int,
    val subjects: List<SubjectInput> = emptyList()
)

data class DepartmentInput(
    val name: String,
    val total_semesters: Int = 8,
    val semesters: List<SemesterInput> = emptyList()
)

data class ProgramInput(
    val name: String,
    val departments: List<DepartmentInput> = emptyList()
)

data class CurriculumSetupRequest(
    val uni_key: String,
    val programs: List<ProgramInput>
)

data class SetupCurriculumResponse(
    val success: Boolean,
    val message: String,
    val subjects_created: Int = 0,
    val groups_created: Int = 0
)

// ── Curriculum Hierarchy Read Models (Tree for Teacher/Student) ───────────────

data class SubjectResponse(
    val id: Int,
    val name: String,
    val code: String? = null,
    val group_name: String? = null   // set by backend when a class group already exists for this subject
)

data class SemesterResponse(
    val id: Int,
    val semester_num: Int,
    val subjects: List<SubjectResponse> = emptyList()
)

data class DepartmentResponse(
    val id: Int,
    val name: String,
    val total_semesters: Int,
    val semesters: List<SemesterResponse> = emptyList()
)

data class ProgramResponse(
    val id: Int,
    val name: String,
    val departments: List<DepartmentResponse> = emptyList()
)

data class CurriculumResponse(
    val uni_key: String,
    val university_name: String,
    val programs: List<ProgramResponse> = emptyList()
)

// ── Teacher & Student Academic Action Models ──────────────────────────────────

data class TeacherSubjectAssignmentRequest(
    val teacher_id: Int,
    val uni_key: String,
    val subject_ids: List<Int>
)

data class TeacherAssignmentResponse(
    val success: Boolean,
    val assigned_groups_count: Int,
    val message: String
)

data class StudentAutoEnrollRequest(
    val student_id: Int,
    val uni_key: String,
    val program_name: String,
    val department_name: String,
    val semester_num: Int
)

data class StudentAutoEnrollResponse(
    val success: Boolean,
    val enrolled_groups_count: Int,
    val group_names: List<String> = emptyList(),
    val message: String
)

// ── Founder Email OTP Verification Models ─────────────────────────────────────

data class FounderOTPRequest(
    val university_id: Int,
    val founder_email: String
)

data class FounderOTPResponse(
    val success: Boolean,
    val message: String
)

data class FounderVerifyOTPRequest(
    val university_id: Int,
    val otp_code: String
)

data class FounderVerifyOTPResponse(
    val success: Boolean,
    val status: String,    // "pending_admin_review"
    val message: String
)

data class UniversityStatusResponse(
    val id: Int,
    val name: String,
    val country: String,
    val uni_key: String?,
    val founder_email: String?,
    val status: String,    // pending_email_verification | pending_admin_review | verified | rejected
    val created_at: String
)

// ── Incremental Curriculum & Teacher Claim Models ─────────────────────────────

data class AddNewSubjectRequest(
    val uni_key: String,
    val program_name: String,
    val department_name: String,
    val semester_num: Int,
    val subject_name: String,
    val subject_code: String? = null
)

data class AddNewSubjectResponse(
    val success: Boolean,
    val subject_id: Int,
    val group_id: Int,
    val group_name: String,
    val students_auto_enrolled_count: Int,
    val message: String
)

data class UnclaimedSubjectResponse(
    val subject_id: Int,
    val group_id: Int,
    val group_name: String,
    val subject_name: String,
    val program_name: String,
    val department_name: String,
    val semester_num: Int
)

data class UnclaimedSubjectsListResponse(
    val total: Int,
    val unclaimed_subjects: List<UnclaimedSubjectResponse> = emptyList()
)

data class ClaimSubjectRequest(
    val uni_key: String,
    val subject_id: Int,
    val teacher_id: Int
)

data class ClaimSubjectResponse(
    val success: Boolean,
    val group_id: Int,
    val group_name: String,
    val teacher_id: Int,
    val message: String
)


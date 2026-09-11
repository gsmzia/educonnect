from pydantic import BaseModel
from typing import List, Optional
import datetime

# ── University Verification & Key Schemas ─────────────────────────────────────

class UniversityVerifyRequest(BaseModel):
    name: str
    country: str
    # Founder identity is deliberately taken from the signed Bearer session,
    # never from client-controlled JSON.

class UniversityResponse(BaseModel):
    id: int
    name: str
    country: str
    uni_key: str
    founder_id: Optional[int]
    created_at: str

    class Config:
        from_attributes = True

class ValidateKeyResponse(BaseModel):
    valid: bool
    university_id: Optional[int] = None
    name: Optional[str] = None
    country: Optional[str] = None
    uni_key: Optional[str] = None
    message: Optional[str] = None

# ── Curriculum Setup Schemas (Founder Wizard Input) ───────────────────────────

class SubjectInput(BaseModel):
    name: str
    code: Optional[str] = None

class SemesterInput(BaseModel):
    semester_num: int
    subjects: List[SubjectInput] = []

class DepartmentInput(BaseModel):
    name: str
    total_semesters: int = 8
    semesters: List[SemesterInput] = []

class ProgramInput(BaseModel):
    name: str  # "BS", "MS", "PhD", or custom
    departments: List[DepartmentInput] = []

class CurriculumSetupRequest(BaseModel):
    uni_key: str
    programs: List[ProgramInput]

# ── Curriculum Hierarchy Read Schemas ─────────────────────────────────────────

class SubjectResponse(BaseModel):
    id: int
    name: str
    code: Optional[str] = None

    class Config:
        from_attributes = True

class SemesterResponse(BaseModel):
    id: int
    semester_num: int
    subjects: List[SubjectResponse] = []

    class Config:
        from_attributes = True

class DepartmentResponse(BaseModel):
    id: int
    name: str
    total_semesters: int
    semesters: List[SemesterResponse] = []

    class Config:
        from_attributes = True

class ProgramResponse(BaseModel):
    id: int
    name: str
    departments: List[DepartmentResponse] = []

    class Config:
        from_attributes = True

class CurriculumResponse(BaseModel):
    uni_key: str
    university_name: str
    programs: List[ProgramResponse] = []

# ── Teacher & Student Academic Actions ────────────────────────────────────────

class TeacherSubjectAssignmentRequest(BaseModel):
    teacher_id: int
    uni_key: str
    subject_ids: List[int]

class TeacherAssignmentResponse(BaseModel):
    success: bool
    assigned_groups_count: int
    message: str

class StudentAutoEnrollRequest(BaseModel):
    student_id: int
    uni_key: str
    program_name: str
    department_name: str
    semester_num: int

class StudentAutoEnrollResponse(BaseModel):
    success: bool
    enrolled_groups_count: int
    group_names: List[str] = []
    message: str

# ── Founder Email OTP Verification Schemas ─────────────────────────────────────

class FounderOTPRequest(BaseModel):
    university_id: int
    founder_email: str   # must end with .edu / .edu.pk / .ac. domain

class FounderOTPResponse(BaseModel):
    success: bool
    message: str

class FounderVerifyOTPRequest(BaseModel):
    university_id: int
    otp_code: str

class FounderVerifyOTPResponse(BaseModel):
    success: bool
    status: str           # "pending_admin_review"
    message: str

# ── University Status Response (extended ValidateKey) ─────────────────────────

class UniversityStatusResponse(BaseModel):
    id: int
    name: str
    country: str
    uni_key: Optional[str] = None
    founder_email: Optional[str] = None
    status: str           # pending_email_verification | pending_admin_review | verified | rejected
    created_at: str

    class Config:
        from_attributes = True

# ── Admin Panel Schemas ────────────────────────────────────────────────────────

class AdminVerifyFounderResponse(BaseModel):
    success: bool
    university_id: int
    new_status: str
    message: str

# ── Incremental Curriculum & Teacher Claim Schemas ─────────────────────────────

class AddNewSubjectRequest(BaseModel):
    uni_key: str
    program_name: str
    department_name: str
    semester_num: int
    subject_name: str
    subject_code: Optional[str] = None

class AddNewSubjectResponse(BaseModel):
    success: bool
    subject_id: int
    group_id: int
    group_name: str
    students_auto_enrolled_count: int
    message: str

class UnclaimedSubjectResponse(BaseModel):
    subject_id: int
    group_id: int
    group_name: str
    subject_name: str
    program_name: str
    department_name: str
    semester_num: int

class ClaimSubjectRequest(BaseModel):
    uni_key: str
    subject_id: int
    teacher_id: int

class ClaimSubjectResponse(BaseModel):
    success: bool
    group_id: int
    group_name: str
    teacher_id: int
    message: str


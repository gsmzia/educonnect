import re
import os
import random
import secrets
import datetime
from fastapi import APIRouter, Depends, HTTPException, status, Header
from sqlalchemy.orm import Session
from sqlalchemy import func
from sqlalchemy.exc import IntegrityError

from database import get_db
import models
from auth import get_current_user
from academic.schemas import (
    UniversityVerifyRequest,
    UniversityResponse,
    ValidateKeyResponse,
    CurriculumSetupRequest,
    CurriculumResponse,
    ProgramResponse,
    DepartmentResponse,
    SemesterResponse,
    SubjectResponse,
    TeacherSubjectAssignmentRequest,
    TeacherAssignmentResponse,
    StudentAutoEnrollRequest,
    StudentAutoEnrollResponse,
    FounderOTPRequest,
    FounderOTPResponse,
    FounderVerifyOTPRequest,
    FounderVerifyOTPResponse,
    UniversityStatusResponse,
    AdminVerifyFounderResponse,
    AddNewSubjectRequest,
    AddNewSubjectResponse,
    UnclaimedSubjectResponse,
    ClaimSubjectRequest,
    ClaimSubjectResponse,
)

router = APIRouter()

# ── Helper: Unique Key Generator ──────────────────────────────────────────────

def _generate_unique_uni_key(country: str, name: str, db: Session) -> str:
    """
    Generates a human-readable, unique University Key.
    Example: EDU-PK-NUST-7429
    """
    # Country code: 2 letters
    c_clean = re.sub(r'[^A-Za-z]', '', country).upper()
    c_code = c_clean[:2] if len(c_clean) >= 2 else "GL"

    # Name acronym or prefix: 3-4 letters
    words = [w for w in re.split(r'\s+', name.strip()) if w]
    if len(words) >= 3:
        acronym = "".join(w[0] for w in words[:4]).upper()
    elif len(words) == 2:
        acronym = (words[0][:2] + words[1][:2]).upper()
    elif words:
        acronym = words[0][:4].upper()
    else:
        acronym = "UNI"

    # This is an invitation identifier, not the authorization mechanism.  It
    # still needs enough entropy that it cannot be guessed or enumerated.
    for _ in range(50):
        random_suffix = secrets.token_hex(12).upper()  # 96 cryptographic bits
        candidate_key = f"EDU-{c_code}-{acronym}-{random_suffix}"
        exists = db.query(models.University).filter(
            models.University.uni_key == candidate_key
        ).first()
        if not exists:
            return candidate_key

    raise RuntimeError("Unable to allocate a unique university key")


def _role_is(user: models.User, expected: str) -> bool:
    return (user.role or "").strip().lower() == expected.lower()


def _find_university_or_404(uni_key: str, db: Session) -> models.University:
    uni = db.query(models.University).filter(
        models.University.uni_key == uni_key.strip().upper()
    ).first()
    if not uni:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="University not found for this key.")
    return uni


def _require_founder(uni: models.University, current_user: models.User) -> None:
    if uni.founder_id != current_user.id or not current_user.is_founder:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only this university's verified founder can change its curriculum.",
        )


# ── 1. Founder: Register University & Generate Key ───────────────────────────

@router.post("/verify", response_model=UniversityResponse)
def verify_university(
    req: UniversityVerifyRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    if (getattr(current_user, "active_session_mode", "academic") or "academic").lower() == "simple":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Switch back to academic mode before managing a university.")
    clean_name = req.name.strip()
    clean_country = req.country.strip()

    if not clean_name or not clean_country:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="University name and country are required."
        )

    # Check if university with same name & country already exists
    existing = db.query(models.University).filter(
        func.lower(models.University.name) == clean_name.lower(),
        func.lower(models.University.country) == clean_country.lower()
    ).first()

    if existing:
        # A client-provided founder_id was an account-takeover vulnerability.
        # Existing campuses can only be reopened by their original founder.
        if existing.founder_id != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="This university is already registered by another founder.",
            )
        return UniversityResponse(
            id=existing.id,
            name=existing.name,
            country=existing.country,
            uni_key=existing.uni_key,
            founder_id=existing.founder_id,
            created_at=existing.created_at.isoformat() if existing.created_at else ""
        )

    if current_user.university_id is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Your account is already connected to a university and cannot register another one.",
        )

    # The user comes from the signed Bearer session, never from request JSON.
    # One commit makes the campus and founder binding indivisible.
    uni = models.University(
        name=clean_name,
        country=clean_country,
        uni_key=_generate_unique_uni_key(clean_country, clean_name, db),
        founder_id=current_user.id,
    )
    try:
        db.add(uni)
        db.flush()
        current_user.university_id = uni.id
        current_user.is_founder = True
        db.commit()
        db.refresh(uni)
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="University registration conflicted. Please try again.") from exc

    return UniversityResponse(
        id=uni.id,
        name=uni.name,
        country=uni.country,
        uni_key=uni.uni_key,
        founder_id=uni.founder_id,
        created_at=uni.created_at.isoformat() if uni.created_at else ""
    )


# ── 2. Validate University Key (Used in Signup) ───────────────────────────────

@router.get("/validate-key/{key}", response_model=ValidateKeyResponse)
def validate_university_key(key: str, db: Session = Depends(get_db)):
    clean_key = key.strip().upper()
    uni = db.query(models.University).filter(
        models.University.uni_key == clean_key
    ).first()

    if not uni:
        return ValidateKeyResponse(
            valid=False,
            message="Invalid University Key. Please check with your campus administrator."
        )

    # Key only works for enrollment once admin has verified the university
    uni_status = getattr(uni, "status", "verified") or "verified"  # backward-compat: old rows have no status
    if uni_status == "pending_email_verification":
        return ValidateKeyResponse(
            valid=False,
            university_id=uni.id,
            name=uni.name,
            country=uni.country,
            message=f"{uni.name} — Founder has not verified their email yet. Enrollment unavailable."
        )
    if uni_status == "pending_admin_review":
        return ValidateKeyResponse(
            valid=False,
            university_id=uni.id,
            name=uni.name,
            country=uni.country,
            message=f"{uni.name} — University is under admin review. Enrollment will open once approved."
        )
    if uni_status == "rejected":
        return ValidateKeyResponse(
            valid=False,
            message="This university key has been rejected by the admin."
        )

    return ValidateKeyResponse(
        valid=True,
        university_id=uni.id,
        name=uni.name,
        country=uni.country,
        # The caller already supplied this key.  Never echo a credential-like
        # value in a response where logs/proxies might retain it.
        uni_key=None,
        message=f"Verified: {uni.name} ({uni.country})"
    )


# ── 3. Founder: Setup Complete Academic Curriculum Tree ───────────────────────

@router.post("/curriculum/setup")
def setup_curriculum(
    req: CurriculumSetupRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    # Locking the university serializes two accidental founder submissions.  All
    # writes below are flushed but committed exactly once, so a failure cannot
    # leave a half-created curriculum or orphan official groups behind.
    uni = db.query(models.University).filter(
        models.University.uni_key == req.uni_key.strip().upper()
    ).with_for_update().first()
    if not uni:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="University not found for this key.")
    _require_founder(uni, current_user)

    created_subjects_count = 0
    created_groups_count = 0
    try:
        for prog_in in req.programs:
            prog_name = prog_in.name.strip()
            if not prog_name:
                continue
            program = db.query(models.AcademicProgram).filter(
                models.AcademicProgram.university_id == uni.id,
                func.lower(models.AcademicProgram.name) == prog_name.lower(),
            ).first()
            if not program:
                program = models.AcademicProgram(university_id=uni.id, name=prog_name)
                db.add(program)
                db.flush()

            for dept_in in prog_in.departments:
                dept_name = dept_in.name.strip()
                if not dept_name:
                    continue
                total_semesters = max(1, min(dept_in.total_semesters or 8, 20))
                dept = db.query(models.AcademicDepartment).filter(
                    models.AcademicDepartment.program_id == program.id,
                    func.lower(models.AcademicDepartment.name) == dept_name.lower(),
                ).first()
                if not dept:
                    dept = models.AcademicDepartment(
                        program_id=program.id, name=dept_name, total_semesters=total_semesters
                    )
                    db.add(dept)
                    db.flush()
                else:
                    dept.total_semesters = total_semesters

                for sem_in in dept_in.semesters:
                    sem_num = sem_in.semester_num
                    if sem_num <= 0 or sem_num > total_semesters:
                        raise HTTPException(
                            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                            detail=f"Semester {sem_num} is outside the configured range for {dept_name}.",
                        )
                    semester = db.query(models.AcademicSemester).filter(
                        models.AcademicSemester.department_id == dept.id,
                        models.AcademicSemester.semester_num == sem_num,
                    ).first()
                    if not semester:
                        semester = models.AcademicSemester(department_id=dept.id, semester_num=sem_num)
                        db.add(semester)
                        db.flush()

                    for subj_in in sem_in.subjects:
                        subj_name = subj_in.name.strip()
                        if not subj_name:
                            continue
                        subject = db.query(models.AcademicSubject).filter(
                            models.AcademicSubject.semester_id == semester.id,
                            func.lower(models.AcademicSubject.name) == subj_name.lower(),
                        ).first()
                        if not subject:
                            subject = models.AcademicSubject(
                                semester_id=semester.id,
                                name=subj_name,
                                code=subj_in.code.strip() if subj_in.code else None,
                            )
                            db.add(subject)
                            db.flush()
                            created_subjects_count += 1

                        group = db.query(models.Group).filter(
                            models.Group.university_id == uni.id,
                            models.Group.academic_subject_id == subject.id,
                        ).first()
                        if not group:
                            group = models.Group(
                                name=f"{subj_name} ({prog_name} {dept_name} - Sem {sem_num})",
                                degree=prog_name,
                                major=dept_name,
                                academic_year=f"Semester {sem_num}",
                                university_id=uni.id,
                                academic_subject_id=subject.id,
                                academic_semester_id=semester.id,
                            )
                            db.add(group)
                            db.flush()
                            created_groups_count += 1

                        # Auto-enroll any students already enrolled in this semester
                        enrolled_students = db.query(models.User).filter(
                            models.User.university_id == uni.id,
                            func.lower(models.User.degree) == prog_name.lower(),
                            func.lower(models.User.major) == dept_name.lower(),
                            models.User.semester == sem_num,
                            func.lower(models.User.role) == "student"
                        ).all()
                        for st in enrolled_students:
                            m = db.query(models.GroupStudent).filter(
                                models.GroupStudent.group_id == group.id,
                                models.GroupStudent.student_id == st.id
                            ).first()
                            if not m:
                                db.add(models.GroupStudent(group_id=group.id, student_id=st.id, is_left=False))
        db.commit()
    except HTTPException:
        db.rollback()
        raise
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Curriculum changed concurrently. Reload the curriculum and submit again.",
        ) from exc
    except Exception:
        db.rollback()
        raise

    return {
        "success": True,
        "message": "Curriculum and official class groups configured successfully!",
        "subjects_created": created_subjects_count,
        "groups_created": created_groups_count,
    }


# ── 4. Get University Curriculum Tree ─────────────────────────────────────────

@router.get("/curriculum/{uni_key}", response_model=CurriculumResponse)
def get_curriculum_tree(
    uni_key: str,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    uni = _find_university_or_404(uni_key, db)
    # A new teacher/student needs this tree once to choose their official
    # subjects before the subsequent protected assignment/enrolment binds the
    # account.  A user already bound to another university may never browse it.
    if current_user.university_id is not None and current_user.university_id != uni.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Your account is not a member of this university.",
        )

    programs = db.query(models.AcademicProgram).filter(
        models.AcademicProgram.university_id == uni.id
    ).order_by(models.AcademicProgram.id).all()

    prog_responses = []
    for prog in programs:
        departments = db.query(models.AcademicDepartment).filter(
            models.AcademicDepartment.program_id == prog.id
        ).order_by(models.AcademicDepartment.name).all()

        dept_responses = []
        for dept in departments:
            semesters = db.query(models.AcademicSemester).filter(
                models.AcademicSemester.department_id == dept.id
            ).order_by(models.AcademicSemester.semester_num).all()

            sem_responses = []
            for sem in semesters:
                subjects = db.query(models.AcademicSubject).filter(
                    models.AcademicSubject.semester_id == sem.id
                ).order_by(models.AcademicSubject.name).all()

                subj_responses = [
                    SubjectResponse(id=s.id, name=s.name, code=s.code)
                    for s in subjects
                ]
                sem_responses.append(
                    SemesterResponse(
                        id=sem.id,
                        semester_num=sem.semester_num,
                        subjects=subj_responses
                    )
                )

            dept_responses.append(
                DepartmentResponse(
                    id=dept.id,
                    name=dept.name,
                    total_semesters=dept.total_semesters,
                    semesters=sem_responses
                )
            )

        prog_responses.append(
            ProgramResponse(
                id=prog.id,
                name=prog.name,
                departments=dept_responses
            )
        )

    return CurriculumResponse(
        uni_key=uni.uni_key,
        university_name=uni.name,
        programs=prog_responses
    )


# ── 5. Teacher: Assign Subjects & Become Group Admin ──────────────────────────

@router.post("/teacher/assign-subjects", response_model=TeacherAssignmentResponse)
def teacher_assign_subjects(
    req: TeacherSubjectAssignmentRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    if (getattr(current_user, "active_session_mode", "academic") or "academic").lower() == "simple":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Switch back to academic mode before assigning subjects.")
    if req.teacher_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You can only assign subjects to your own account.")
    if not _role_is(current_user, "Teacher"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only Teacher accounts can claim teaching subjects.")
    requested_subject_ids = sorted(set(req.subject_ids))
    if not requested_subject_ids:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Choose at least one subject.")
    clean_key = req.uni_key.strip().upper()
    uni = db.query(models.University).filter(
        models.University.uni_key == clean_key
    ).first()

    if not uni:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="University not found for this key."
        )

    teacher = db.query(models.User).filter(models.User.id == req.teacher_id).first()
    if not teacher:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Teacher user not found."
        )

    # ── Single Active University Tenancy Lock ──────────────────────────────
    if teacher.university_id and teacher.university_id != uni.id:
        current_uni = db.query(models.University).filter(models.University.id == teacher.university_id).first()
        uni_name = current_uni.name if current_uni else "another campus"
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Bhai aap pehle se '{uni_name}' ke verified teacher ho! Doosri university join ya claim nahi kar sakte."
        )

    # Lock every requested official group before deciding assignment.  This
    # prevents two teachers from racing and silently replacing one another.
    groups = db.query(models.Group).filter(
        models.Group.university_id == uni.id,
        models.Group.academic_subject_id.in_(requested_subject_ids)
    ).with_for_update().all()
    if len(groups) != len(requested_subject_ids):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="One or more selected subjects are not official subjects for this university."
        )
    if any(group.teacher_id not in (None, teacher.id) for group in groups):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="One or more selected subjects already have a teacher. Ask the founder to manage reassignment."
        )

    # Bind and assign in the same commit, so a failed assignment cannot leave
    # the account attached to a university without the selected groups.
    teacher.university_id = uni.id

    assigned_count = 0
    for group in groups:
        group.teacher_id = teacher.id
        assigned_count += 1
        membership = db.query(models.GroupStudent).filter(
            models.GroupStudent.group_id == group.id,
            models.GroupStudent.student_id == teacher.id
        ).first()
        if not membership:
            db.add(models.GroupStudent(group_id=group.id, student_id=teacher.id, is_left=False))
        else:
            membership.is_left = False

    try:
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Subject assignment conflicted. Please reload and try again.",
        ) from exc

    return TeacherAssignmentResponse(
        success=True,
        assigned_groups_count=assigned_count,
        message=f"Successfully assigned as instructor to {assigned_count} class group(s)."
    )


# ── 6. Student: Auto-Enroll Into All Semester Subjects ────────────────────────

@router.post("/student/auto-enroll", response_model=StudentAutoEnrollResponse)
def student_auto_enroll(
    req: StudentAutoEnrollRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    if (getattr(current_user, "active_session_mode", "academic") or "academic").lower() == "simple":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Switch back to academic mode before enrolling in classes.")
    if req.student_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You can only enroll your own account.")
    if not _role_is(current_user, "Student"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only Student accounts can enroll in class groups.")
    clean_key = req.uni_key.strip().upper()
    uni = db.query(models.University).filter(
        models.University.uni_key == clean_key
    ).first()

    if not uni:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="University not found for this key."
        )

    student = db.query(models.User).filter(models.User.id == req.student_id).first()
    if not student:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Student user not found."
        )

    # ── Single Active University Tenancy Lock ──────────────────────────────
    if student.university_id and student.university_id != uni.id:
        current_uni = db.query(models.University).filter(models.University.id == student.university_id).first()
        uni_name = current_uni.name if current_uni else "another campus"
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Bhai aap pehle se '{uni_name}' ke verified student ho! Doosri university join ya enroll nahi kar sakte."
        )

    # Find the academic semester
    program = db.query(models.AcademicProgram).filter(
        models.AcademicProgram.university_id == uni.id,
        func.lower(models.AcademicProgram.name) == req.program_name.strip().lower()
    ).first()

    if not program:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Program '{req.program_name}' not found in this university."
        )

    dept = db.query(models.AcademicDepartment).filter(
        models.AcademicDepartment.program_id == program.id,
        func.lower(models.AcademicDepartment.name) == req.department_name.strip().lower()
    ).first()

    if not dept:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Department '{req.department_name}' not found in program '{req.program_name}'."
        )

    sem = db.query(models.AcademicSemester).filter(
        models.AcademicSemester.department_id == dept.id,
        models.AcademicSemester.semester_num == req.semester_num
    ).first()

    if not sem:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Semester {req.semester_num} not configured for this department."
        )

    # Find all official class groups for this semester
    groups = db.query(models.Group).filter(
        models.Group.university_id == uni.id,
        models.Group.academic_semester_id == sem.id
    ).with_for_update().all()

    if not groups:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="This semester has no official class groups yet."
        )

    enrolled_names = []
    # All validation finished before mutating the profile.  The row lock makes
    # duplicate fast taps/devices serialize for this student.
    student = db.query(models.User).filter(models.User.id == current_user.id).with_for_update().one()
    student.university_id = uni.id
    student.degree = req.program_name.strip()
    student.major = req.department_name.strip()
    student.semester = req.semester_num
    student.academic_year = f"Semester {req.semester_num}"
    for g in groups:
        membership = db.query(models.GroupStudent).filter(
            models.GroupStudent.group_id == g.id,
            models.GroupStudent.student_id == student.id
        ).first()

        if not membership:
            membership = models.GroupStudent(
                group_id=g.id,
                student_id=student.id,
                is_left=False
            )
            db.add(membership)
        else:
            membership.is_left = False

        enrolled_names.append(g.name)

    try:
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Enrollment conflicted. Please reopen the screen and try again.",
        ) from exc

    return StudentAutoEnrollResponse(
        success=True,
        enrolled_groups_count=len(enrolled_names),
        group_names=enrolled_names,
        message=f"Enrolled in {len(enrolled_names)} subject groups for Semester {req.semester_num}."
    )


# ═══════════════════════════════════════════════════════════════════════════════
# FOUNDER EMAIL OTP VERIFICATION & ADMIN REVIEW FLOW
# ═══════════════════════════════════════════════════════════════════════════════

def _is_university_email(email: str) -> bool:
    """
    Validates that the email belongs to an institutional domain.
    Accepted patterns: .edu, .edu.pk, .edu.in, .edu.bd, .ac.uk, .ac.in, .ac.nz, etc.
    """
    email = email.lower().strip()
    patterns = [
        r"\.edu$",
        r"\.edu\.[a-z]{2}$",
        r"\.ac\.[a-z]{2}$",
        r"\.ac$",
    ]
    domain_part = email.split("@")[-1] if "@" in email else ""
    return any(re.search(p, domain_part) for p in patterns)


# ── 7. Founder: Request Email OTP ─────────────────────────────────────────────

@router.post("/founder/request-email-otp", response_model=FounderOTPResponse)
def founder_request_email_otp(
    req: FounderOTPRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """
    Step 1 of founder verification.
    Sends a 6-digit OTP to the founder's institutional email address.
    Only the founder of the university may call this.
    """
    uni = db.query(models.University).filter(
        models.University.id == req.university_id
    ).first()

    if not uni:
        raise HTTPException(status_code=404, detail="University not found.")

    if uni.founder_id != current_user.id:
        raise HTTPException(status_code=403, detail="Only the founder of this university can verify it.")

    if getattr(uni, "status", None) == "verified":
        return FounderOTPResponse(success=True, message="University is already verified.")

    if not _is_university_email(req.founder_email):
        raise HTTPException(
            status_code=400,
            detail="Please use your official university email (e.g. admin@university.edu.pk). "
                   "Personal email addresses (Gmail, Yahoo, Hotmail) are not accepted."
        )

    otp_code = str(random.randint(100000, 999999))
    expires_at = datetime.datetime.utcnow() + datetime.timedelta(minutes=10)

    uni.founder_email = req.founder_email.lower().strip()
    uni.founder_otp = otp_code
    uni.founder_otp_expires_at = expires_at
    db.commit()

    # Import and use the existing send_otp_email from main.py
    try:
        import sys
        import importlib.util
        main_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "main.py")
        spec = importlib.util.spec_from_file_location("main_module", main_path)
        main_mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(main_mod)
        send_otp_email = getattr(main_mod, "send_otp_email", None)
        if send_otp_email:
            send_otp_email(req.founder_email, otp_code)
        else:
            print(f"[FOUNDER OTP SIMULATED] Email: {req.founder_email} | OTP: {otp_code}")
    except Exception as e:
        # Never fail the request just because email sending failed
        print(f"[FOUNDER OTP EMAIL ERROR] {e} | Fallback OTP for {req.founder_email}: {otp_code}")

    return FounderOTPResponse(
        success=True,
        message=f"OTP sent to {req.founder_email}. Valid for 10 minutes."
    )


# ── 8. Founder: Verify Email OTP ──────────────────────────────────────────────

@router.post("/founder/verify-email-otp", response_model=FounderVerifyOTPResponse)
def founder_verify_email_otp(
    req: FounderVerifyOTPRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """
    Step 2 of founder verification.
    Validates the OTP and promotes university status to 'pending_admin_review'.
    """
    uni = db.query(models.University).filter(
        models.University.id == req.university_id
    ).first()

    if not uni:
        raise HTTPException(status_code=404, detail="University not found.")

    if uni.founder_id != current_user.id:
        raise HTTPException(status_code=403, detail="Only the founder of this university can verify it.")

    stored_otp = getattr(uni, "founder_otp", None)
    expires_at = getattr(uni, "founder_otp_expires_at", None)

    if not stored_otp:
        raise HTTPException(status_code=400, detail="No OTP found. Please request a new OTP first.")

    if expires_at and datetime.datetime.utcnow() > expires_at:
        raise HTTPException(status_code=400, detail="OTP has expired. Please request a new one.")

    if req.otp_code.strip() != stored_otp:
        raise HTTPException(status_code=400, detail="Incorrect OTP. Please try again.")

    # OTP matched — move to pending_admin_review
    uni.status = "pending_admin_review"
    uni.founder_otp = None          # invalidate after use
    uni.founder_otp_expires_at = None
    db.commit()

    return FounderVerifyOTPResponse(
        success=True,
        status="pending_admin_review",
        message="Email verified! Your university is now under admin review. You will be notified when approved."
    )


# ── 9. Founder: Get University Status ─────────────────────────────────────────

@router.get("/founder/status/{university_id}", response_model=UniversityStatusResponse)
def get_university_status(
    university_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """Returns the current verification status for the founder's university."""
    uni = db.query(models.University).filter(
        models.University.id == university_id
    ).first()

    if not uni:
        raise HTTPException(status_code=404, detail="University not found.")

    if uni.founder_id != current_user.id:
        raise HTTPException(status_code=403, detail="Access denied.")

    return UniversityStatusResponse(
        id=uni.id,
        name=uni.name,
        country=uni.country,
        uni_key=uni.uni_key,
        founder_email=getattr(uni, "founder_email", None),
        status=getattr(uni, "status", "verified") or "verified",
        created_at=uni.created_at.isoformat() if uni.created_at else ""
    )


# ═══════════════════════════════════════════════════════════════════════════════
# ADMIN PANEL ENDPOINTS  (protected by ADMIN_SECRET env variable)
# ═══════════════════════════════════════════════════════════════════════════════

def _verify_admin_secret(x_admin_secret: str = Header(None)) -> None:
    """Dependency: validates the X-Admin-Secret header."""
    admin_secret = (os.environ.get("ADMIN_SECRET") or "").strip()
    if not admin_secret:
        raise HTTPException(status_code=503, detail="Admin panel is not configured on this server.")
    if not x_admin_secret or x_admin_secret.strip() != admin_secret:
        raise HTTPException(status_code=403, detail="Invalid admin secret.")


# ── 10. Admin: List Pending Founder Applications ──────────────────────────────

@router.get("/admin/pending-founders")
def admin_list_pending_founders(
    db: Session = Depends(get_db),
    _: None = Depends(_verify_admin_secret),
):
    """
    Returns all universities awaiting admin review.
    Protected by X-Admin-Secret header.
    """
    unis = db.query(models.University).filter(
        models.University.status == "pending_admin_review"
    ).order_by(models.University.created_at.desc()).all()

    result = []
    for u in unis:
        founder = db.query(models.User).filter(models.User.id == u.founder_id).first()
        result.append({
            "id": u.id,
            "name": u.name,
            "country": u.country,
            "uni_key": u.uni_key,
            "founder_id": u.founder_id,
            "founder_name": founder.name if founder else "Unknown",
            "founder_username": founder.username if founder else "Unknown",
            "founder_email": getattr(u, "founder_email", None),
            "status": getattr(u, "status", "pending_admin_review"),
            "created_at": u.created_at.isoformat() if u.created_at else "",
        })

    return {"total": len(result), "universities": result}


# ── 11. Admin: All Universities (for full dashboard) ─────────────────────────

@router.get("/admin/all-universities")
def admin_all_universities(
    db: Session = Depends(get_db),
    _: None = Depends(_verify_admin_secret),
):
    """Returns all universities with their current status."""
    unis = db.query(models.University).order_by(models.University.created_at.desc()).all()

    result = []
    for u in unis:
        founder = db.query(models.User).filter(models.User.id == u.founder_id).first()
        result.append({
            "id": u.id,
            "name": u.name,
            "country": u.country,
            "uni_key": u.uni_key,
            "founder_id": u.founder_id,
            "founder_name": founder.name if founder else "Unknown",
            "founder_username": founder.username if founder else "Unknown",
            "founder_email": getattr(u, "founder_email", None),
            "status": getattr(u, "status", "verified") or "verified",
            "created_at": u.created_at.isoformat() if u.created_at else "",
        })

    return {"total": len(result), "universities": result}


# ── 12. Admin: Verify or Reject a Founder Application ────────────────────────

@router.post("/admin/set-university-status/{university_id}", response_model=AdminVerifyFounderResponse)
def admin_set_university_status(
    university_id: int,
    new_status: str,          # "verified" or "rejected"
    db: Session = Depends(get_db),
    _: None = Depends(_verify_admin_secret),
):
    """
    Admin approves or rejects a university.
    new_status: 'verified' or 'rejected'
    Protected by X-Admin-Secret header.
    """
    if new_status not in ("verified", "rejected"):
        raise HTTPException(status_code=400, detail="new_status must be 'verified' or 'rejected'.")

    uni = db.query(models.University).filter(
        models.University.id == university_id
    ).first()

    if not uni:
        raise HTTPException(status_code=404, detail="University not found.")

    uni.status = new_status
    db.commit()

    action = "approved" if new_status == "verified" else "rejected"
    return AdminVerifyFounderResponse(
        success=True,
        university_id=uni.id,
        new_status=new_status,
        message=f"University '{uni.name}' has been {action}."
    )

# ═══════════════════════════════════════════════════════════════════════════════
# FOUNDER INCREMENTAL CURRICULUM MANAGEMENT & TEACHER SUBJECT CLAIMING
# ═══════════════════════════════════════════════════════════════════════════════

# ── 13. Founder: Add Single Subject (with Student Auto-Enrollment) ────────────

@router.post("/curriculum/add-subject", response_model=AddNewSubjectResponse)
def founder_add_new_subject(
    req: AddNewSubjectRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """
    Allows Founder to add a new subject to an existing (or new) semester.
    Automatically:
    1. Creates/finds Program -> Department -> Semester -> Subject.
    2. Creates the official class Group.
    3. Finds all students currently in this semester and auto-enrolls them into the new group.
    """
    uni = _find_university_or_404(req.uni_key, db)
    _require_founder(uni, current_user)

    prog_name = req.program_name.strip()
    dept_name = req.department_name.strip()
    subj_name = req.subject_name.strip()
    sem_num = req.semester_num

    if not prog_name or not dept_name or not subj_name or sem_num <= 0:
        raise HTTPException(status_code=400, detail="Program, Department, Subject name and valid Semester are required.")

    # 1. Program
    program = db.query(models.AcademicProgram).filter(
        models.AcademicProgram.university_id == uni.id,
        func.lower(models.AcademicProgram.name) == prog_name.lower()
    ).first()
    if not program:
        program = models.AcademicProgram(university_id=uni.id, name=prog_name)
        db.add(program)
        db.flush()

    # 2. Department
    dept = db.query(models.AcademicDepartment).filter(
        models.AcademicDepartment.program_id == program.id,
        func.lower(models.AcademicDepartment.name) == dept_name.lower()
    ).first()
    if not dept:
        dept = models.AcademicDepartment(program_id=program.id, name=dept_name, total_semesters=max(sem_num, 8))
        db.add(dept)
        db.flush()
    elif sem_num > dept.total_semesters:
        dept.total_semesters = sem_num

    # 3. Semester
    semester = db.query(models.AcademicSemester).filter(
        models.AcademicSemester.department_id == dept.id,
        models.AcademicSemester.semester_num == sem_num
    ).first()
    if not semester:
        semester = models.AcademicSemester(department_id=dept.id, semester_num=sem_num)
        db.add(semester)
        db.flush()

    # 4. Subject
    subject = db.query(models.AcademicSubject).filter(
        models.AcademicSubject.semester_id == semester.id,
        func.lower(models.AcademicSubject.name) == subj_name.lower()
    ).first()
    if not subject:
        subject = models.AcademicSubject(
            semester_id=semester.id,
            name=subj_name,
            code=req.subject_code.strip() if req.subject_code else None
        )
        db.add(subject)
        db.flush()

    # 5. Official Class Group
    group_display_name = f"{subj_name} ({prog_name} {dept_name} - Sem {sem_num})"
    group = db.query(models.Group).filter(
        models.Group.university_id == uni.id,
        models.Group.academic_subject_id == subject.id
    ).first()

    if not group:
        group = models.Group(
            name=group_display_name,
            degree=prog_name,
            major=dept_name,
            academic_year=f"Semester {sem_num}",
            university_id=uni.id,
            academic_subject_id=subject.id,
            academic_semester_id=semester.id,
        )
        db.add(group)
        db.flush()

    # 6. Auto-Enroll all matching students currently in this semester
    students = db.query(models.User).filter(
        models.User.university_id == uni.id,
        func.lower(models.User.degree) == prog_name.lower(),
        func.lower(models.User.major) == dept_name.lower(),
        models.User.semester == sem_num,
        func.lower(models.User.role) == "student"
    ).all()

    auto_enrolled_count = 0
    for st in students:
        membership = db.query(models.GroupStudent).filter(
            models.GroupStudent.group_id == group.id,
            models.GroupStudent.student_id == st.id
        ).first()
        if not membership:
            db.add(models.GroupStudent(group_id=group.id, student_id=st.id, is_left=False))
            auto_enrolled_count += 1
        elif membership.is_left:
            membership.is_left = False
            auto_enrolled_count += 1

    db.commit()

    return AddNewSubjectResponse(
        success=True,
        subject_id=subject.id,
        group_id=group.id,
        group_name=group.name,
        students_auto_enrolled_count=auto_enrolled_count,
        message=f"Subject '{subj_name}' added! Group created and {auto_enrolled_count} student(s) auto-enrolled."
    )


# ── 14. Teacher: List Unclaimed Subjects in University ────────────────────────

@router.get("/teacher/unclaimed-subjects/{uni_key}")
def teacher_list_unclaimed_subjects(
    uni_key: str,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """
    Returns all official class groups in this campus that currently have no instructor (teacher_id IS NULL).
    Used by Teacher Dashboard to show broadcast claim popups.
    """
    uni = _find_university_or_404(uni_key, db)
    if not _role_is(current_user, "Teacher"):
        raise HTTPException(status_code=403, detail="Only Teacher accounts can view unclaimed subjects.")

    unclaimed_groups = db.query(models.Group).filter(
        models.Group.university_id == uni.id,
        models.Group.academic_subject_id != None,
        models.Group.teacher_id == None
    ).order_by(models.Group.id.desc()).all()

    result = []
    for g in unclaimed_groups:
        subj = db.query(models.AcademicSubject).filter(models.AcademicSubject.id == g.academic_subject_id).first()
        sem = db.query(models.AcademicSemester).filter(models.AcademicSemester.id == g.academic_semester_id).first()
        dept = db.query(models.AcademicDepartment).filter(models.AcademicDepartment.id == sem.department_id).first() if sem else None
        prog = db.query(models.AcademicProgram).filter(models.AcademicProgram.id == dept.program_id).first() if dept else None

        result.append({
            "subject_id": g.academic_subject_id,
            "group_id": g.id,
            "group_name": g.name,
            "subject_name": subj.name if subj else g.name,
            "program_name": prog.name if prog else (g.degree or "Academic"),
            "department_name": dept.name if dept else (g.major or ""),
            "semester_num": sem.semester_num if sem else 1,
        })

    return {"total": len(result), "unclaimed_subjects": result}


# ── 15. Teacher: Claim an Unclaimed Subject (Become Instructor & Group Admin) ──

@router.post("/teacher/claim-subject", response_model=ClaimSubjectResponse)
def teacher_claim_subject(
    req: ClaimSubjectRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    """
    Allows a Teacher to claim an unassigned subject.
    Sets Group.teacher_id to the claiming teacher and adds them to GroupStudent.
    First-come, first-assigned with database row-level locking.
    """
    if req.teacher_id != current_user.id:
        raise HTTPException(status_code=403, detail="You can only claim subjects for your own account.")
    if not _role_is(current_user, "Teacher"):
        raise HTTPException(status_code=403, detail="Only Teacher accounts can claim teaching subjects.")

    uni = _find_university_or_404(req.uni_key, db)

    # Tenancy lock check
    if current_user.university_id and current_user.university_id != uni.id:
        curr_uni = db.query(models.University).filter(models.University.id == current_user.university_id).first()
        u_name = curr_uni.name if curr_uni else "another university"
        raise HTTPException(
            status_code=409,
            detail=f"Bhai aap pehle se '{u_name}' ke verified teacher ho! Doosri university ka subject claim nahi kar sakte."
        )

    # Row-lock group to prevent simultaneous double claiming
    group = db.query(models.Group).filter(
        models.Group.university_id == uni.id,
        models.Group.academic_subject_id == req.subject_id
    ).with_for_update().first()

    if not group:
        raise HTTPException(status_code=404, detail="Official class group not found for this subject.")

    if group.teacher_id is not None:
        if group.teacher_id == current_user.id:
            return ClaimSubjectResponse(
                success=True,
                group_id=group.id,
                group_name=group.name,
                teacher_id=current_user.id,
                message=f"You are already the instructor for '{group.name}'."
            )
        assigned_teacher = db.query(models.User).filter(models.User.id == group.teacher_id).first()
        t_name = assigned_teacher.name if assigned_teacher else "another teacher"
        raise HTTPException(
            status_code=409,
            detail=f"Subject has already been claimed by {t_name}."
        )

    # Assign teacher
    group.teacher_id = current_user.id
    current_user.university_id = uni.id

    # Add teacher as group member/admin
    membership = db.query(models.GroupStudent).filter(
        models.GroupStudent.group_id == group.id,
        models.GroupStudent.student_id == current_user.id
    ).first()
    if not membership:
        db.add(models.GroupStudent(group_id=group.id, student_id=current_user.id, is_left=False))
    else:
        membership.is_left = False

    db.commit()

    return ClaimSubjectResponse(
        success=True,
        group_id=group.id,
        group_name=group.name,
        teacher_id=current_user.id,
        message=f"Success! You are now the official instructor and admin for '{group.name}'."
    )


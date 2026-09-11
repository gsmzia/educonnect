<div align="center">
  <img src="backend/static/educonnect_logo.png" width="130" alt="EduConnect Logo" />
  <h1>EduConnect</h1>
  <p><strong>A Multi-Tenant Academic Operating System & Campus Collaboration Platform</strong></p>
  <p>Engineered with Jetpack Compose (Modern Android M3) & FastAPI (High-Performance Python Asynchronous Backend)</p>

  <p>
    <img src="https://img.shields.io/badge/Platform-Android_API_26+-3DDC84?style=flat&logo=android&logoColor=white" alt="Android" />
    <img src="https://img.shields.io/badge/Backend-FastAPI_0.100+-009688?style=flat&logo=fastapi&logoColor=white" alt="FastAPI" />
    <img src="https://img.shields.io/badge/Database-SQLAlchemy_2.0-red?style=flat&logo=sqlite&logoColor=white" alt="Database" />
    <img src="https://img.shields.io/badge/Architecture-Clean_Architecture_%2B_MVI-blue?style=flat" alt="Clean Architecture" />
    <img src="https://img.shields.io/badge/Storage-Tenant_Isolated_Scoped_Storage-orange?style=flat" alt="Scoped Storage" />
    <img src="https://img.shields.io/badge/Status-Active_Development_(WIP)-yellow?style=flat" alt="WIP" />
  </p>
</div>

---

> [!NOTE]
> **Project Status — Active Engineering & Continuous Iteration (WIP)**  
> EduConnect is an ongoing, production-grade engineering endeavor. The multi-tenant hierarchy, institutional cryptographic verification, academic data propagation, and tenant-scoped media pipelines are fully functional and tested. However, active development is underway to refine UI edge cases, optimize offline-first synchronization, and integrate our upcoming campus AI assistant.

---

## 📑 Table of Contents
1. [The Vision & Problem Statement](#-the-vision--problem-statement)
2. [How EduConnect Operates (Core Workflows)](#-how-educonnect-operates-core-workflows)
   - [Founder Verification & Institutional Proof](#1-founder-verification--institutional-proof)
   - [Auto-Saved Multi-Step Curriculum Builder](#2-auto-saved-multi-step-curriculum-builder)
   - [Dynamic Student Auto-Enrollment & Subject Propagation](#3-dynamic-student-auto-enrollment--subject-propagation)
   - [Teacher Subject Claiming & Group Governance](#4-teacher-subject-claiming--group-governance)
   - [Tenant-Isolated Scoped Storage System](#5-tenant-isolated-scoped-storage-system)
   - [Resumable Chunked Transfers & Ephemeral Cloud Pipeline](#6-resumable-chunked-transfers--ephemeral-cloud-pipeline)
3. [Comprehensive System Architecture](#-comprehensive-system-architecture)
   - [High-Level System Topology](#high-level-system-topology)
   - [Data Flow & Inter-Component Communication Diagram](#data-flow--inter-component-communication-diagram)
4. [API Specification & Communication Protocols](#-api-specification--communication-protocols)
5. [Complete Repository Directory Map (File-by-File Breakdown)](#-complete-repository-directory-map)
   - [Backend Architecture (`backend/`)](#backend-directory-breakdown)
   - [Android Architecture (`app/src/main/java/`)](#android-client-directory-breakdown)
   - [Android Resources & Native Configurations (`app/src/main/res/`)](#android-resources--system-configurations)
   - [Root Project & Build System](#root-project-configuration)
6. [Security, Privacy & Leak Prevention](#-security-privacy--leak-prevention)
7. [Future Roadmap: Autonomous Campus AI Assistant](#-future-roadmap-autonomous-campus-ai-assistant)
8. [Engineering Methodology & Claude AI Partnership](#-engineering-methodology--claude-ai-partnership)
9. [Local Development & Setup Guide](#-local-development--setup-guide)
10. [Technical Specifications](#-technical-specifications)

---

## 💡 The Vision & Problem Statement

Higher education communication across universities is fragmented and insecure:
- **Disorganized Channels**: Class discussions, lecture notes, and assignments are lost across informal group chats tied to personal phone numbers.
- **Zero Institutional Verification**: Anyone can create unverified campus groups, leading to impersonation, academic fraud, and spam.
- **Manual Overhead**: When a university department adds a new subject to a semester, hundreds of students must manually search for links, request invites, and coordinate with teachers.
- **Privacy Pollution**: Personal photos, voice notes, and private family media get mixed together in device galleries with formal lecture PDFs and exam papers.

**EduConnect transforms this paradigm.** It is an **Academic Operating System** designed to reflect the real-world organizational hierarchy of higher education directly in software:
- **Institutions are Cryptographically Verified**: Only authenticated university founders using `.edu` institutional emails can establish campus entities.
- **Curriculums Dynamically Generate Class Spaces**: Degree programs, departments, semesters, and course codes automatically instantiate instructor-moderated digital lecture halls.
- **Instant Student Propagation**: Enrolling in a degree/semester immediately routes students into all official course groups without a single invite link.
- **OS-Level Scoped Storage Isolation**: Academic course materials and personal communication files are stored in completely separate operating system directory hierarchies.

---

## 🔄 How EduConnect Operates (Core Workflows)

```
                            [ University Founder ]
                                       │
                                       ▼
                       Official Institutional Email (.edu)
                                       │
                                       ▼
                         Cryptographic 6-Digit OTP Check
                                       │
                                       ▼
                             Admin Approval Queue
                                       │
                                       ▼
                        Curriculum Setup & Auto-Save
                       (Programs ➔ Semesters ➔ Subjects)
                                       │
                                       ▼
                           University Key Generated
                                       │
                ┌──────────────────────┴──────────────────────┐
                ▼                                             ▼
          [ Teachers ]                                  [ Students ]
                │                                             │
      Receives Course Alert                        Enters University Key &
                │                                  Selects Degree / Semester
                ▼                                             │
      Claims Unassigned Subject                               ▼
                │                                   Instant Auto-Enrollment
                ▼                                   In All Semester Subjects
     Official Course Instructor                               │
      & Group Admin Privileges                                ▼
                │                                    Syncs Course Materials
                └──────────────────────┬──────────────────────┘
                                       │
                                       ▼
                      [ Active Academic Collaboration ]
                   • Scoped Storage Separation on Device
                   • Ephemeral Media Cloud Lifecycle
                   • Offline-First Room DB Synchronization
```

### 1. Founder Verification & Institutional Proof
1. A campus administrator or founder registers their institution by providing legal university credentials and a verified institutional email domain (e.g. `chancellor@university.edu.pk`).
2. The backend generates a time-sensitive 6-digit cryptographic OTP dispatched via institutional mail.
3. Upon OTP validation, the institution enters an administrative security queue. Once approved, a unique, cryptographically derived **University Key** is activated.

### 2. Auto-Saved Multi-Step Curriculum Builder
- Setting up a university curriculum involves hundreds of courses across multiple faculties (e.g., *Faculty of Computing ➔ BS Computer Science ➔ Semesters 1 to 8 ➔ ~6 subjects per semester*).
- EduConnect incorporates a **Real-Time Persistent Auto-Save Wizard** in Jetpack Compose:
  - Every keystroke, department definition, and subject addition is committed to local persistent storage (`SharedPreferences` / Room cache) and synchronized with backend draft endpoints.
  - Founders can safely close the app, switch tasks, or complete setup across multiple days without losing a single course entry.
  - Supports bulk fast-pasting of syllabus subject lists and incremental publishing.

### 3. Dynamic Student Auto-Enrollment & Subject Propagation
- Students download EduConnect, select the Student role, and input their campus **University Key**.
- Upon selecting their enrolled Degree Program and current Semester (e.g., *BS Software Engineering — Semester 3*), the client dispatches an enrollment request.
- The backend maps the academic relational tree and **instantly auto-enrolls the student into all official subject chat groups** for that semester.
- **Dynamic Propagation**: If the founder adds a new subject (e.g. *Artificial Intelligence Lab*) to an existing semester later in the term, the backend automatically cascades this update and joins all currently enrolled students without any manual action required.

### 4. Teacher Subject Claiming & Group Governance
- When a curriculum is published, subjects without an assigned instructor enter an open unassigned registry.
- Verified faculty members receive real-time dashboard notifications listing unassigned courses within their department.
- A teacher can claim a subject via a two-step confirmation modal. Once claimed, the backend assigns the teacher as the official course instructor, grants administrative privileges in the class chat group, and locks the subject from conflicting claims.

### 5. Tenant-Isolated Scoped Storage System
- EduConnect implements strict multi-tenant sandboxing on the Android filesystem:
  - **University Tenant Storage**: `Android/media/com.security.myapplication/EDUConnect/<University_Name>/EDUConnect Chats/`
    - Subdivided into `EDUConnect Files`, `EDUConnect Images`, `EDUConnect Audio`, `EDUConnect Video`.
  - **Simple User / Personal Storage**: `Android/media/com.security.myapplication/EDUConnect/Simple User/`
- All storage directories are configured with persistent user data preservation flags, ensuring notes, assignments, and lectures remain on the device even across application updates.

### 6. Resumable Chunked Transfers & Ephemeral Cloud Pipeline
- Large lecture recordings, syllabus PDFs, and lab assets are processed through an intelligent transfer pipeline:
  - **Hardware Compression**: Images and videos are compressed on-device using Android hardware codecs (`MediaCompressor.kt`) before network dispatch.
  - **Persistent Transfer Queue**: Uploads and downloads are tracked in a dedicated Room database (`TransferDatabase.kt`), managed by Android WorkManager for automatic resumption after connectivity interruptions.
  - **Ephemeral Cloud Storage**: Attachments are stored on Cloudinary with unique asset tags and automatic TTL purge policies to protect student privacy and minimize infrastructure bloat.

---

## 🏛️ Comprehensive System Architecture

### High-Level System Topology

```
┌───────────────────────────────────────────────────────────────────────────┐
│                      ANDROID CLIENT (Jetpack Compose)                     │
│                                                                           │
│  ┌───────────────────────┐  ┌──────────────────────┐  ┌────────────────┐  │
│  │   UI Presentation     │  │  State Management    │  │  Local Storage │  │
│  │  • M3 Composables     │  │  • Kotlin Flow       │  │  • Room DB     │  │
│  │  • Custom Layouts     │  │  • StateFlow / MVI   │  │  • Tenant Dir  │  │
│  │  • Motion & Gestures  │  │  • Lifecycle Scopes  │  │  • Fast Cache  │  │
│  └──────────┬────────────┘  └──────────┬───────────┘  └────────┬───────┘  │
│             │                          │                       │          │
│             └───────────────────┐      │      ┌────────────────┘          │
│                                 ▼      ▼      ▼                           │
│                     ┌──────────────────────────────────────┐              │
│                     │       Repository & Transfer Layer    │              │
│                     │  • OfflineFirstRepository.kt         │              │
│                     │  • TransferManager.kt (WorkManager)  │              │
│                     │  • EduConnectStorageManager.kt       │              │
│                     └──────────────────┬───────────────────┘              │
│                                        │                                  │
│                                        ▼                                  │
│                     ┌──────────────────────────────────────┐              │
│                     │        Network Engine (Retrofit)     │              │
│                     │  • ApiClient.kt (Bearer Interceptor) │              │
│                     │  • WebSocket Client (Real-time WS)   │              │
│                     └──────────────────┬───────────────────┘              │
└────────────────────────────────────────┼──────────────────────────────────┘
                                         │
                         HTTPS REST / TLS WebSockets
                                         │
┌────────────────────────────────────────┴──────────────────────────────────┐
│                      BACKEND SERVICES (FastAPI / ASGI)                    │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                     API Gateway & Security Layer                    │  │
│  │  • auth.py (HMAC-SHA256 Token Auth & PBKDF2 Password Hashing)       │  │
│  │  • CORS Middleware & Request Throttling                             │  │
│  └─────────────────────────────────┬───────────────────────────────────┘  │
│                                    │                                      │
│        ┌───────────────────────────┴───────────────────────────┐          │
│        ▼                                                       ▼          │
│  ┌───────────────────────────────┐       ┌─────────────────────────────┐  │
│  │       Academic Engine         │       │    Real-Time Messaging      │  │
│  │  • Founder & .edu OTP Verify  │       │  • WebSocket Connection Mgr │  │
│  │  • Curriculum Auto-Save API   │       │  • Ephemeral Chats & Ticks  │  │
│  │  • Auto-Enrollment Cascade    │       │  • Typing Indicators        │  │
│  │  • Teacher Claim Orchestrator │       │  • Campus Social Feed       │  │
│  └──────────────┬────────────────┘       └──────────────┬──────────────┘  │
│                 │                                       │                 │
│                 └───────────────────┬───────────────────┘                 │
│                                     │                                     │
│                                     ▼                                     │
│                     ┌───────────────────────────────┐                     │
│                     │     Data Access Layer (ORM)   │                     │
│                     │  • SQLAlchemy 2.0 Models      │                     │
│                     │  • Pydantic v2 Schemas        │                     │
│                     │  • SQLite (WAL) / PostgreSQL  │                     │
│                     └───────────────┬───────────────┘                     │
└─────────────────────────────────────┼─────────────────────────────────────┘
                                      │
               ┌──────────────────────┴──────────────────────┐
               ▼                                             ▼
┌─────────────────────────────┐               ┌─────────────────────────────┐
│  Cloudinary Ephemeral Media │               │   Firebase Cloud Messaging  │
│  • CDN Media Optimization   │               │   • High-Priority Push      │
│  • Auto-Purge Scheduled Job │               │   • Native Heads-Up Alerts  │
└─────────────────────────────┘               └─────────────────────────────┘
```

### Data Flow & Inter-Component Communication Diagram

```
[Android Client Screen]
        │
        │ 1. User Action (e.g., Post Announcement, Send Message, Enroll)
        ▼
[Compose ViewModel / MVI State]
        │
        │ 2. Check Local Cache (Offline-First)
        ▼
[Room Database / MessageDatabase] ───(Instant UI Render)───► [Display to User]
        │
        │ 3. Asynchronous Dispatch
        ▼
[OfflineFirstRepository / TransferManager]
        │
        │ 4. HTTP POST / WebSocket Frame with HMAC Bearer Token
        ▼
[FastAPI REST API / WebSockets (main.py / academic/routes.py)]
        │
        ├─► [auth.py: Validate Session Token & Permissions]
        │
        ├─► [database.py / models.py: Atomic Transaction Write]
        │
        ├─► [fcm_service.py: Dispatch Background Push Notification to Target Users]
        │
        └─► [cloudinary_service.py: Ephemeral Asset Registration & Expiry Stamping]
```

---

## 📡 API Specification & Communication Protocols

### 1. Authentication & Security
| Method | Endpoint | Request Body | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/register` | `UserCreate` (username, email, password, role) | Registers student, teacher, founder, or simple user. |
| `POST` | `/token` | `OAuth2PasswordRequestForm` (username, password) | Issues HMAC-SHA256 authenticated session token. |
| `POST` | `/forgot-password` | `{ "email": "string" }` | Generates and sends account recovery OTP code. |
| `POST` | `/reset-password` | `{ "email": "...", "code": "...", "new_password": "..." }` | Validates recovery OTP and resets user password. |

### 2. Academic Hierarchy & Institution Verification
| Method | Endpoint | Request Body | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/academic/verify-university` | `{ "university_name": "...", "domain": "...", "official_email": "..." }` | Initiates founder registration and dispatches 6-digit institutional OTP. |
| `POST` | `/academic/verify-otp` | `{ "university_id": 1, "otp_code": "123456" }` | Validates OTP and places university in admin verification queue. |
| `GET` | `/academic/verification-status` | Header: `Bearer <token>` | Queries current approval status of the founder's campus. |
| `POST` | `/academic/curriculum/autosave` | `CurriculumDraftRequest` | Saves partial in-progress curriculum wizard draft for multi-session editing. |
| `GET` | `/academic/curriculum/autosave` | Query: `university_id` | Fetches active saved curriculum draft for the founder. |
| `POST` | `/academic/curriculum/publish` | `CurriculumPublishRequest` (Hierarchy tree) | Commits full curriculum; automatically creates official class groups. |
| `POST` | `/academic/curriculum/add-incremental` | `IncrementalCurriculumRequest` | Appends new programs/subjects and auto-propagates them to existing students. |
| `POST` | `/academic/student/enroll` | `{ "university_key": "...", "program_id": 1, "semester_number": 1 }` | Enrolls student and auto-joins all semester subject groups. |
| `GET` | `/academic/teacher/available-subjects` | Query: `university_id` | Retrieves unassigned semester subjects available for faculty claim. |
| `POST` | `/academic/teacher/claim-subject` | `{ "subject_id": 1, "teacher_id": 1 }` | Assigns verified teacher as course instructor and group administrator. |

### 3. Messaging & Real-Time Gateway
| Protocol | Endpoint / Channel | Payload | Description |
| :--- | :--- | :--- | :--- |
| `WS` | `/ws/{user_id}` | JSON Event Frames | Bidirectional WebSocket for real-time text delivery, typing indicators, and read receipts. |
| `GET` | `/messages/personal/{target_id}` | Query: `offset`, `limit` | Paginated 1-on-1 chat history retrieval. |
| `POST` | `/messages/personal` | `MessageCreate` | Sends direct message with optional ephemeral media metadata. |
| `GET` | `/groups/{group_id}/messages` | Query: `offset`, `limit` | Retrieves official class group discussions and announcements. |
| `POST` | `/groups/{group_id}/messages` | `GroupMessageCreate` | Posts an announcement or message in an academic course group. |

### 4. Ephemeral Media & Campus Social Feed
| Method | Endpoint | Request Body | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/upload/media` | Multipart Form: File, Category, TTL | Uploads file to Cloudinary CDN; returns secure ephemeral URI. |
| `POST` | `/media/receipt` | `{ "media_id": 1, "status": "downloaded" }` | Confirms receipt and local device caching. |
| `GET` | `/posts` | Query: `page`, `page_size` | Campus-wide announcement and social feed stream. |
| `POST` | `/posts` | `PostCreate` (content, media_urls) | Publishes new campus announcement. |
| `POST` | `/posts/{id}/like` | None | Toggles like reaction on a campus post. |
| `GET` | `/posts/{id}/comments`| Query: `post_id` | Retrieves nested comment hierarchy for a campus post. |
| `POST` | `/posts/{id}/comments`| `{ "content": "string" }` | Submits comment on an announcement. |

---

## 📁 Complete Repository Directory Map

Below is an exhaustive breakdown of every directory and file in the EduConnect codebase, explaining its exact engineering purpose.

### Backend Directory Breakdown (`backend/`)

```text
backend/
├── academic/                           # Academic Hierarchy & Institutional Module
│   ├── __init__.py                     # Marks academic directory as a Python package
│   ├── routes.py                       # FastAPI routes: Founder verification, OTP, auto-save wizard, enrollment, and course claims
│   └── schemas.py                      # Pydantic v2 schemas: Validates all incoming payloads for university structures
│
├── static/                             # Static Web & Media Assets
│   └── educonnect_logo.png             # Official high-resolution EduConnect branding logo
│
├── admin_panel.html                    # Lightweight HTML/JS super-admin dashboard for reviewing institutional verification requests
├── auth.py                             # Cryptographic HMAC-SHA256 session token manager, password hashing, and route guards
├── cloudinary_service.py               # Cloudinary CDN wrapper handling ephemeral uploads and scheduled media purges
├── database.py                         # SQLAlchemy engine setup, session factories, and SQLite WAL/Postgres connection pooling
├── fcm_service.py                      # Firebase Cloud Messaging service for dispatching high-priority push notifications
├── HORIZONTAL_SCALING.md               # Technical guide for deploying EduConnect across Redis, Celery, and multi-node clusters
├── logo_foremail.html                  # Responsive HTML email component embedding the EduConnect logo in OTP emails
├── main.py                             # Main FastAPI entry point: Lifespan handlers, WebSocket hub, and core messaging endpoints
├── models.py                           # Complete SQLAlchemy ORM schema: Defines Universities, Programs, Subjects, Groups, Users, Posts
├── Procfile                            # Production process descriptor for cloud container platforms (Uvicorn ASGI runner)
├── requirements.txt                    # Pinned Python package dependencies (FastAPI, SQLAlchemy, Uvicorn, Pydantic, etc.)
└── SECURITY_CONFIG.md                  # Comprehensive security reference documenting authentication, secrets, and leak prevention
```

---

### Android Client Directory Breakdown (`app/src/main/java/com/security/myapplication/`)

#### 1. Academic Package (`academic/`)
- `AcademicDataModels.kt`: Strongly typed Kotlin data classes representing Universities, Degree Programs, Semesters, Subjects, and Founder verification states.
- `FounderCurriculumWizardScreen.kt`: Multi-stage Compose wizard with real-time persistent auto-saving, bulk subject paste parser, and publish workflow.
- `StudentEnrollmentScreen.kt`: Student onboarding interface for entering campus keys, selecting degree programs, and initiating automated group enrollment.
- `TeacherSubjectPickerScreen.kt`: Faculty portal displaying unassigned semester courses with single-click claim and confirmation modals.
- `UniversityVerificationScreen.kt`: Founder registration interface handling institutional `.edu` email input and 6-digit OTP verification.

#### 2. Audio Engine (`audio/`)
- `GlobalAudioPlayerIx.kt`: Application-wide singleton managing MediaPlayer lifecycle, audio focus handling, seek positions, and background playback.
- `MiniAudioPlayerBarIx.kt`: Persistent floating audio widget that docks above bottom navigation, allowing users to listen to lectures while browsing other screens.
- `VoiceNoteRecorderIx.kt`: High-performance audio recorder featuring real-time waveform visualization, amplitude sampling, pause/resume, and AAC compression.

#### 3. Core Models (`models/`)
- `DataModels.kt`: Primary client domain entities including `User`, `Message`, `Group`, `Attachment`, `Session`, and delivery status enums.
- `MediaManager.kt`: Native file helper handling MIME detection, thumbnail extraction, gallery scans, and file size formatting.
- `PersonalMediaReceiptReporter.kt`: Network callback service that reports media delivery and local download completion to the backend.

#### 4. Networking Engine (`network/`)
- `ApiClient.kt`: Central Retrofit 2 & OkHttp 3 HTTP client configuration; injects HMAC Bearer authentication tokens, sets timeouts, and declares API contracts.

#### 5. Notifications Subsystem (`notifications/`)
- `AppMessageNotificationWatcher.kt`: Real-time state observer that suppresses notification sounds when the user is currently viewing the active conversation.
- `AppNotificationManager.kt`: Builds custom Android notification channels and interactive layouts (inline reply, direct playback).
- `BatteryOptimizationHelper.kt`: Utility checking OEM battery saver restrictions and guiding users to disable aggressive background killing.
- `BatteryOptimizationNotice.kt`: Material 3 prompt explaining why background execution permissions are required for prompt push delivery.
- `EduConnectFCMService.kt`: `FirebaseMessagingService` implementation handling incoming cloud messaging payloads and routing to local managers.
- `NotificationAudioReceiver.kt`: `BroadcastReceiver` handling audio play/pause controls directly from Android notification media buttons.
- `NotificationDeduplicator.kt`: Message deduplication cache preventing duplicate notifications during network flapping or reconnects.
- `NotificationReplyReceiver.kt`: `BroadcastReceiver` capturing quick inline text replies sent from the system notification shade.
- `NotificationReplyWorker.kt`: Android `WorkManager` background task ensuring inline replies are retried and delivered even under unstable connections.
- `NotificationRouter.kt`: Intent dispatcher parsing notification deep links and navigating the user directly to the target chat or course group.
- `NotificationSyncService.kt`: Android foreground service maintaining notification channel synchronization with backend state.

#### 6. Offline-First Caching (`offline/`)
- `ChatOfflineCache.kt`: Memory and disk caching coordinator enabling instantaneous chat loading prior to network response.
- `MessageDatabase.kt`: Primary Room database defining local message tables, indexes, and Data Access Objects (DAOs).
- `OfflineCacheDatabase.kt`: Secondary Room database caching university curriculums, group memberships, and user profile metadata.
- `OfflineFirstRepository.kt`: Repository layer orchestrating offline reads from Room and transparent background synchronization via Retrofit.

#### 7. Campus Social Feed (`posts/`)
- `CommentsBottomSheet.kt`: Interactive modal bottom sheet displaying nested comments and allowing inline comment creation on posts.
- `CreatePostScreen.kt`: Rich composer screen for drafting campus-wide announcements with multi-media attachments and tagging.
- `PostItem.kt`: Reusable Jetpack Compose card rendering formatted post text, image/video carousels, and like/comment counters.
- `PostMedia.kt`: Media renderer optimizing image grid displays and inline video playback inside feed items.
- `PostModels.kt`: Strongly typed data representations for campus posts, announcement types, reactions, and comment threads.
- `PostPlaybackPositionStore.kt`: In-memory store saving video playback offsets so videos resume seamlessly during feed scrolling.
- `PostRepository.kt`: Data repository managing paginated feed retrieval, reaction toggles, and post creation.
- `PostsScreen.kt`: Main campus feed screen featuring tabbed filtering between official university notices and student discussions.
- `SessionDraftStore.kt`: Local persistent cache preserving unsaved post drafts across app minimizations and restarts.

#### 8. Screens & UI Presentation (`screens/`)
- `AssignmentsTab.kt`: Course assignments hub where teachers publish coursework deadlines and students track submissions.
- `AttendanceTab.kt`: Visual attendance tracker rendering student presence percentages, excused absences, and session histories.
- `CameraScreen.kt`: Custom CameraX implementation supporting photo capture, video recording, front/back toggle, and flash controls.
- `ChatTab.kt`: Academic group chat screen displaying class discussions, instructor notices, and lecture materials.
- `DashboardScreen.kt`: Primary student & founder dashboard showing enrolled subjects, attendance metrics, and quick action cards.
- `ForgotPasswordScreen.kt`: Password recovery interface guiding users through institutional email input and OTP verification.
- `GroupInfoScreen.kt`: Detailed group view displaying course instructor badges, enrolled member rosters, and shared media galleries.
- `LoginScreen.kt`: Institutional login screen with credential inputs, biometric authentication triggers, and role-based redirects.
- `PersonalChatScreen.kt`: 1-on-1 encrypted messaging interface featuring ephemeral media sending, voice notes, and read receipts.
- `PersonalUserInfoScreen.kt`: User profile inspection screen showing institutional affiliation, department, and direct messaging triggers.
- `ProfileScreen.kt`: Account settings view allowing profile picture updates, theme preferences, and secure logout.
- `SignupScreen.kt`: Registration screen with role selectors (Student, Teacher, Founder, Simple User) and input validation.
- `SimpleUserDashboardScreen.kt`: Streamlined messaging interface for external personal contacts not affiliated with a campus tenant.
- `TeacherDashboardScreen.kt`: Faculty command center showing claimed courses, student rosters, and unassigned course alert banners.
- `UnifiedHomeScreen.kt`: Root navigation scaffold managing bottom tabs (Chats, Feed, Academic Dashboard, Profile) and drawer state.
- `UploadStateManager.kt`: Reactive state holder broadcasting upload percentages and retry triggers across all active screens.

#### 9. Tenant Storage Controller (`storage/`)
- `EduConnectStorageManager.kt`: Native storage engine that enforces multi-tenant directory partitioning (`EDUConnect/<University Name>/`).
- `SessionModeManager.kt`: Contextual state manager tracking whether the active session is in Academic Mode or Simple User Mode.

#### 10. Transfer Engine (`transfer/`)
- `MediaCompressor.kt`: Hardware-accelerated media processor optimizing images and transcoding videos to reduce network usage.
- `TransferAutoResumeWatcher.kt`: Network connectivity listener that automatically unpauses queued transfers when Wi-Fi or data restores.
- `TransferDao.kt`: Room Data Access Object for inserting, querying, and updating active file transfer records.
- `TransferDatabase.kt`: Dedicated local Room database persisting chunked upload and download queues across app restarts.
- `TransferManager.kt`: Central transfer orchestrator coordinating chunked streaming, retry exponential backoff, and progress broadcasts.
- `TransferNotificationHelper.kt`: Builds persistent foreground notification progress bars during heavy media downloads and uploads.
- `TransferRecord.kt`: Room database entity storing file paths, server URLs, transfer direction, bytes transferred, and status flags.
- `TransferStateHolder.kt`: Reactive Kotlin StateFlow bridge emitting real-time transfer progress updates to Compose UI components.
- `TransferStatus.kt`: Enum defining transfer lifecycle states (`QUEUED`, `IN_PROGRESS`, `PAUSED`, `COMPLETED`, `FAILED`, `CANCELLED`).
- `TransferWorker.kt`: Android `CoroutineWorker` executing background transfers reliably under Android OS battery constraints.

#### 11. Theme & Design System (`ui/theme/`)
- `AppMotion.kt`: Motion choreography definitions (spring physics, ease curves, slide-ins) for Compose transitions.
- `Color.kt`: Semantic color palette tokens for Dark and Light themes (Teal, Deep Slate, Academic Navy, Accent Gold).
- `Theme.kt`: Material 3 theme wrapper configuring dynamic color schemes, surface elevation, and system bars.
- `Type.kt`: Typography hierarchy specifying custom font families, font weights, and text styling standards.

---

### Android Resources & System Configurations (`app/src/main/res/`)

- `drawable/`: Vector graphics and iconography (`ic_badge_audio`, `ic_badge_photo`, `ic_grad_cap`, `ic_notif_play`, `ic_notif_pause`, waveform bars).
- `layout/`: RemoteViews XML layouts for rich custom Android notifications:
  - `notification_custom_message.xml` & `notification_custom_message_expanded.xml`: Custom notification cards with sender avatar and quick actions.
  - `notification_custom_voice.xml` & `notification_voice_message.xml`: Interactive notification layout with playable voice note seekbar.
- `mipmap-*/`: App launcher icons across all screen densities (MDPI, HDPI, XHDPI, XXHDPI, XXXHDPI, and vector adaptive XML).
- `raw/educonnect_notification.mp3`: Custom high-clarity notification audio chime for incoming messages and class announcements.
- `values/`: Colors (`colors.xml`), string localizations (`strings.xml`), and theme styles (`themes.xml`).
- `xml/backup_rules.xml` & `xml/data_extraction_rules.xml`: Android 12+ backup configuration ensuring sensitive tokens are excluded from cloud backup.
- `xml/file_paths.xml`: `FileProvider` definition granting secure, temporary content URIs for camera capture and media sharing.
- `xml/network_security_config.xml`: Declarative network security policy enforcing TLS encryption for all API endpoints.

---

### Root Project Configuration

- `build.gradle.kts`: Root Gradle build configuration orchestrating plugins, compiler options, and repository repositories.
- `settings.gradle.kts`: Gradle project settings file registering the `:app` Android module and dependency resolution modes.
- `gradle.properties`: JVM optimization arguments, parallel execution flags, and AndroidX feature toggles.
- `.gitignore`: Enterprise-grade exclusion file blocking sensitive environment keys, database files, and local build artifacts.
- `app/google-services.json.example`: Sanitized configuration template demonstrating required Firebase parameters without exposing production keys.

---

## 🔒 Security, Privacy & Leak Prevention

EduConnect is built with strict privacy-by-design standards:
1. **Zero Secret Leaks in Git**:
   - Production secrets (`.env`, `serviceAccountKey.json`, and `google-services.json`) are strictly excluded from version control.
   - All backend credentials (`ADMIN_SECRET`, `AUTH_SESSION_SECRET`, `CLOUDINARY_*`, `RESEND_API_KEY`) are resolved dynamically from OS environment variables.
   - Clean, sanitized example templates (`google-services.json.example`) are provided for open-source contributors.
2. **Stateless HMAC-SHA256 Token Authentication**:
   - Session tokens are signed using cryptographic HMAC keys with strict expiration timestamps, preventing session tampering and replay attacks.
3. **Tenant Storage Boundary Enforcement**:
   - Scoped storage logic ensures one university tenant can never access or read media files belonging to another institution or personal chat space.
4. **Automated Ephemeral Cloud Purge**:
   - Academic attachments and media files on Cloudinary are tagged with expiration timestamps. A scheduled backend maintenance task sweeps and deletes expired media from cloud storage.

---

## 🤖 Future Roadmap: Autonomous Campus AI Assistant

We are actively designing a native **Autonomous Campus AI Assistant** into EduConnect. Our roadmap milestones include:

```
[ Unstructured Campus Data ]
(Syllabus PDFs, Course Guides)
              │
              ▼
┌───────────────────────────┐
│     EduConnect AI Engine  │
│  • LLM Document Parsing   │
│  • Entity Extraction      │
│  • Relationship Mapping   │
└─────────────┬─────────────┘
              │
              ▼
┌───────────────────────────┐
│  Automated Actions Taken  │
│  • Instant Curriculum Gen │
│  • Study Group Formations │
│  • Smart Exam Revision    │
└───────────────────────────┘
```

1. **AI-Powered Curriculum Onboarding**:
   - Rather than manually entering degree structures, founders will be able to upload campus course catalog PDFs. The AI agent will parse degree programs, department hierarchies, semester breakdowns, and course codes—generating the entire university tree automatically.
2. **Autonomous Campus Study Companion**:
   - An in-app intelligent assistant that helps students discover peer study partners, summarize shared lecture materials, and query past exam topics through conversational voice and text.
3. **Voice-to-Action Academic Commands**:
   - Enabling teachers and administrators to perform administrative workflows (e.g. *"Schedule a makeup lab for Operating Systems this Friday at 3 PM and notify all enrolled students"*) through natural voice commands.

---

## 🤝 Engineering Methodology & Claude AI Partnership

EduConnect is conceived, architected, and continuously developed by human technical leadership in collaboration with **Claude**:
- **Human System Architect**: Designed the multi-tenant data model, institutional verification flow, scoped filesystem taxonomy, and user experience requirements.
- **Claude as an AI Engineering Collaborator**:
  - Leveraged as an advanced pair-programmer across both the Kotlin (Jetpack Compose) client and the Python (FastAPI) asynchronous backend.
  - Co-developed type-safe MVI UI components, complex Room database migration strategies, and resilient WorkManager background tasks.
  - Formulated automated test suites to rigorously verify edge cases in curriculum publishing and student group auto-enrollment.

This collaborative engineering model showcases how an ambitious developer can harness AI to build a resilient, production-ready system with institutional-grade standards.

---

## 🛠️ Local Development & Setup Guide

### 1. Prerequisites
- **Android Studio Ladybug (2024.2+)** or later
- **JDK 17** (configured in Android Studio)
- **Python 3.11+**
- **Android Device or Emulator** running Android API 26 (Android 8.0) or higher

### 2. Backend Setup
```bash
# Navigate to the backend directory
cd backend

# Create a virtual environment
python -m venv venv

# Activate virtual environment
# Windows:
.\venv\Scripts\activate
# Linux/macOS:
source venv/bin/activate

# Install dependencies
pip install -r requirements.txt

# Run the FastAPI server with auto-reload
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```
The interactive Swagger API documentation will be available at `http://localhost:8000/docs`.

### 3. Android Client Setup
1. Open the root directory in **Android Studio**.
2. Copy `app/google-services.json.example` to `app/google-services.json` and insert your Firebase project credentials.
3. Update `BASE_URL` in `app/src/main/java/com/security/myapplication/network/ApiClient.kt` to point to your backend server IP.
4. Sync Gradle and run the `:app` module on your target device or emulator.

---

## 📊 Technical Specifications

| Dimension | Specification |
| :--- | :--- |
| **Android Language & SDK** | Kotlin 1.9+, Min SDK 26 (Android 8.0), Target SDK 35 (Android 15) |
| **UI Framework** | Jetpack Compose, Material Design 3 (M3) |
| **Local Persistence** | Room Database (SQLite), Encrypted SharedPreferences |
| **Asynchronous Engine** | Kotlin Coroutines & Reactive Flow (`StateFlow`, `SharedFlow`) |
| **Background Processing**| Android WorkManager & Foreground Services |
| **Networking** | Retrofit 2, OkHttp 3, WebSocket Client |
| **Backend Framework** | FastAPI (Python 3.11), Uvicorn ASGI |
| **Database ORM** | SQLAlchemy 2.0 (SQLite WAL mode / PostgreSQL ready) |
| **Cloud Services** | Cloudinary Ephemeral CDN, Firebase Cloud Messaging (FCM) |
| **Authentication** | Stateless HMAC-SHA256 Bearer Token, PBKDF2 Password Hashing |

---

## 📄 License
This project is proprietary and currently developed for university campus environments. All rights reserved.

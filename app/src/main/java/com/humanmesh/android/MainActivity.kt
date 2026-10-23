package com.humanmesh.android

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humanmesh.android.data.Goal
import com.humanmesh.android.data.GoalOutcome
import com.humanmesh.android.data.ProjectItem
import com.humanmesh.android.data.RoomInvitation
import com.humanmesh.android.data.HumanMeshApi
import com.humanmesh.android.data.MatchRequest
import com.humanmesh.android.data.OpportunityRoom
import com.humanmesh.android.data.RoomMessage
import com.humanmesh.android.data.SessionStore
import com.humanmesh.android.data.SkillMatcher
import com.humanmesh.android.data.UserProfile
import com.humanmesh.android.data.UserSession
import com.humanmesh.android.ui.HumanMeshTheme
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var recoveryAccessToken by mutableStateOf<String?>(null)
    private var emailVerificationCompleted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recoveryAccessToken = extractRecoveryAccessToken(intent)
        emailVerificationCompleted = isEmailVerificationCallback(intent)
        setContent {
            HumanMeshTheme {
                HumanMeshApp(
                    recoveryAccessToken = recoveryAccessToken,
                    emailVerificationCompleted = emailVerificationCompleted,
                    onRecoveryFinished = { recoveryAccessToken = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recoveryAccessToken = extractRecoveryAccessToken(intent)
        emailVerificationCompleted = isEmailVerificationCallback(intent)
    }

    private fun isEmailVerificationCallback(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        return uri.scheme == "humanmesh" && uri.host == "auth" && uri.path == "/verify"
    }

    private fun extractRecoveryAccessToken(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "humanmesh" || uri.host != "auth" || uri.path != "/reset") return null
        val parameters = uri.fragment.orEmpty().split("&").mapNotNull { item ->
            val pair = item.split("=", limit = 2)
            if (pair.size == 2) Uri.decode(pair[0]) to Uri.decode(pair[1]) else null
        }.toMap()
        if (parameters["type"] != "recovery") return null
        return parameters["access_token"]?.takeIf { it.length in 10..8192 }
    }
}

private enum class MainTab(val label: String, val symbol: String) {
    Explore("Explore", "✦"),
    Goals("My goals", "◎"),
    Connect("Connect", "↔"),
    Rooms("Rooms", "◉"),
    Progress("Progress", "✓"),
    Profile("Profile", "○"),
    Safety("Safety", "盾")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HumanMeshApp(
    recoveryAccessToken: String?,
    emailVerificationCompleted: Boolean,
    onRecoveryFinished: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessions = remember { SessionStore(context) }
    val api = remember { HumanMeshApi(sessions) }
    val scope = rememberCoroutineScope()

    val restoredSession = remember { if (api.isConfigured) sessions.read() else null }
    var current by remember { mutableStateOf(restoredSession?.takeIf { api.hasVerifiedEmailMfa(it.accessToken) }) }
    LaunchedEffect(restoredSession) {
        if (restoredSession != null && current == null) sessions.clear()
    }
    var pendingEmailMfaSession by remember { mutableStateOf<UserSession?>(null) }
    var pendingMfaName by remember { mutableStateOf("") }
    var demoMode by rememberSaveable { mutableStateOf(false) }
    var activeTab by rememberSaveable { mutableStateOf(MainTab.Explore) }
    var goals by remember { mutableStateOf(emptyList<Goal>()) }
    var ownGoals by remember { mutableStateOf(emptyList<Goal>()) }
    var rooms by remember { mutableStateOf(emptyList<OpportunityRoom>()) }
    var incomingRequests by remember { mutableStateOf(emptyList<MatchRequest>()) }
    var outgoingRequests by remember { mutableStateOf(emptyList<MatchRequest>()) }
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var memberProfiles by remember { mutableStateOf(emptyMap<String, UserProfile>()) }
    var directoryProfiles by remember { mutableStateOf(emptyList<UserProfile>()) }
    var selectedRoom by remember { mutableStateOf<OpportunityRoom?>(null) }
    var messages by remember { mutableStateOf(emptyList<RoomMessage>()) }
    var selectedProgressRoom by remember { mutableStateOf<OpportunityRoom?>(null) }
    var progressItems by remember { mutableStateOf(emptyList<ProjectItem>()) }
    var goalOutcomes by remember { mutableStateOf(emptyList<GoalOutcome>()) }
    var roomInvitations by remember { mutableStateOf(emptyList<RoomInvitation>()) }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(emailVerificationCompleted) {
        if (emailVerificationCompleted) {
            notice = "Email verified. Sign in with your email and password to continue."
        }
    }
    var busy by remember { mutableStateOf(false) }
    var showGoalDialog by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<Goal?>(null) }
    var deleteGoalTarget by remember { mutableStateOf<Goal?>(null) }
    var showRoomDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(api.isConfigured) {
        if (!api.isConfigured) sessions.clear()
    }

    fun runTask(task: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                task()
            } catch (error: Exception) {
                notice = error.message?.take(180) ?: "Something went wrong. Please try again."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(current?.userId, activeTab, selectedRoom?.id, selectedProgressRoom?.id, demoMode) {
        val signedIn = current
        if (demoMode) {
            if (selectedRoom != null) {
                messages = listOf(
                    RoomMessage("sample-1", "sample-member", "Welcome to the sample room. This is demo content.", "Just now"),
                    RoomMessage("sample-2", "sample-owner", "Share a goal and one next step you can take this week.", "Just now")
                )
            }
        } else if (signedIn != null && api.isConfigured) {
            runCatching {
                profile = api.getProfile(signedIn.userId)
                goals = api.getGoals()
                ownGoals = api.getGoals(ownOnly = true, ownerId = signedIn.userId)
                rooms = api.getRooms()
                directoryProfiles = api.getDirectoryProfiles()
                memberProfiles = api.getProfilesByIds((goals.map { it.ownerId } + signedIn.userId).distinct())
                if (activeTab == MainTab.Connect) {
                    val pair = api.getConnections(signedIn.userId)
                    incomingRequests = pair.first
                    outgoingRequests = pair.second
                    val otherIds = pair.first.map { it.senderId } + pair.second.map { it.receiverId }
                    memberProfiles = memberProfiles + api.getProfilesByIds(otherIds.distinct())
                }
                if (selectedRoom != null) messages = api.getMessages(selectedRoom!!.id)
                if (activeTab == MainTab.Progress) {
                    roomInvitations = api.getRoomInvitations(signedIn.userId)
                    if (selectedProgressRoom != null) {
                        progressItems = api.getProjectItems(selectedProgressRoom!!.id)
                        goalOutcomes = api.getGoalOutcomes(selectedProgressRoom!!.id)
                    }
                }
            }.onFailure {
                notice = it.message?.take(180) ?: "Could not refresh HumanMesh."
            }
        }
    }

    val resetToken = recoveryAccessToken
    if (resetToken != null) {
        PasswordResetScreen(
            loading = busy,
            message = notice,
            onClearMessage = { notice = "" },
            onCancel = {
                notice = "Password reset cancelled. Return to sign in when ready."
                onRecoveryFinished()
            },
            onSubmit = { newPassword ->
                runTask {
                    api.updatePasswordFromRecovery(newPassword, resetToken)
                    sessions.clear()
                    current = null
                    profile = null
                    demoMode = false
                    notice = "Password changed. Sign in using your new password."
                    onRecoveryFinished()
                }
            }
        )
        return
    }
    if (current == null && !demoMode) {
        AuthScreen(
            configured = api.isConfigured,
            loading = busy,
            message = notice,
            onClearMessage = { notice = "" },
            onDemo = {
                demoMode = true
                activeTab = MainTab.Explore
                goals = demoGoals()
                ownGoals = emptyList()
                rooms = demoRooms()
                profile = null
                memberProfiles = demoProfiles()
                directoryProfiles = demoProfiles().values.toList()
                notice = "Demo mode uses sample content only. No real accounts or other people are connected."
            },
            onSignIn = { email, password ->
                runTask {
                    val session = api.signIn(email, password)
                    api.sendEmailMfaCode(session)
                    pendingEmailMfaSession = session
                    pendingMfaName = ""
                    notice = "We sent a six-digit verification code to " + session.email + "."
                }
            },
            onSignUp = { email, password, name ->
                runTask {
                    val session = api.signUp(email, password)
                    if (session == null) {
                        notice = "Account request sent. Verify your email, then return here to sign in."
                    } else {
                        api.sendEmailMfaCode(session)
                        pendingEmailMfaSession = session
                        pendingMfaName = name
                        notice = "We sent a six-digit verification code to " + session.email + "."
                    }
                }
            },
            onForgotPassword = { email ->
                runTask {
                    api.requestPasswordReset(email)
                    notice = "If that email is registered, password reset instructions have been sent."
                }
            },
            pendingVerificationEmail = pendingEmailMfaSession?.email,
            onVerifyEmailCode = { code ->
                runTask {
                    val pending = pendingEmailMfaSession ?: throw IllegalStateException("Sign-in expired. Enter your password again.")
                    api.verifyEmailMfaCode(pending, code)
                    val verifiedSession = api.completeEmailMfa(pending)
                    api.ensureProfile(verifiedSession, pendingMfaName.ifBlank { verifiedSession.email.substringBefore("@").ifBlank { "New member" } })
                    pendingEmailMfaSession = null
                    pendingMfaName = ""
                    current = verifiedSession
                    demoMode = false
                    activeTab = MainTab.Explore
                    notice = "Email verification complete. Welcome to HumanMesh."
                }
            },
            onResendEmailCode = {
                runTask {
                    val pending = pendingEmailMfaSession ?: throw IllegalStateException("Sign-in expired. Enter your password again.")
                    api.sendEmailMfaCode(pending)
                    notice = "A new verification code has been sent to " + pending.email + "."
                }
            },
            onCancelEmailVerification = {
                pendingEmailMfaSession = null
                pendingMfaName = ""
                notice = "Sign-in cancelled. Enter your credentials again."
            }
        )
        return
    }

    val signedIn = current
    Scaffold(
        containerColor = Color(0xFF10111B),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.humanmesh_logo),
                            contentDescription = "HumanMesh logo",
                            modifier = Modifier.size(38.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("HumanMesh", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text(
                                if (demoMode) "LOCAL DEMO · sample content" else "Find your people",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF10111B))
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF191B29)) {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = activeTab == tab,
                        onClick = {
                            selectedRoom = null
                            activeTab = tab
                            notice = ""
                        },
                        icon = { Text(tab.symbol, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                        label = { Text(tab.label, fontSize = 10.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF10111B))
        ) {
            if (notice.isNotBlank()) {
                NoticeBanner(text = notice, onDismiss = { notice = "" })
            }
            if (busy) {
                Text("Working securely…", modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
            }
            if (selectedRoom != null && activeTab == MainTab.Rooms) {
                RoomChatPanel(
                    room = selectedRoom!!,
                    messages = messages,
                    demoMode = demoMode,
                    currentUserId = signedIn?.userId ?: "demo-user",
                    onBack = { selectedRoom = null },
                    onRefresh = {
                        if (demoMode) notice = "Sample room messages are local demo content."
                        else runTask { messages = api.getMessages(selectedRoom!!.id) }
                    },
                    onSend = { message ->
                        if (demoMode) {
                            messages = messages + RoomMessage(UUID.randomUUID().toString(), "demo-user", message, "Just now")
                            notice = "Message added locally in demo mode."
                        } else runTask {
                            api.sendMessage(selectedRoom!!.id, signedIn!!.userId, message)
                            messages = api.getMessages(selectedRoom!!.id)
                        }
                    }
                )
            } else {
                when (activeTab) {
                    MainTab.Explore -> ExplorePanel(
                        goals = goals,
                        currentUserId = signedIn?.userId ?: "demo-user",
                        mySkills = if (demoMode) listOf("Android", "UX research", "Education", "Kotlin") else profile?.skills.orEmpty(),
                        ownerProfiles = memberProfiles,
                        people = directoryProfiles,
                        demoMode = demoMode,
                        onConnect = { goal ->
                            if (demoMode) notice = "Demo only: connections are not sent to real users."
                            else if (signedIn != null) runTask {
                                api.sendConnection(goal.ownerId, goal.id, signedIn.userId)
                                notice = "Connection request sent."
                            }
                        },
                        onRefresh = {
                            if (demoMode) notice = "Sample content refreshed."
                            else runTask {
                                goals = api.getGoals()
                                directoryProfiles = api.getDirectoryProfiles()
                                memberProfiles = memberProfiles + api.getProfilesByIds(goals.map { it.ownerId }.distinct())
                            }
                        }
                    )
                    MainTab.Goals -> MyGoalsPanel(
                        goals = ownGoals,
                        demoMode = demoMode,
                        onAdd = { editingGoal = null; showGoalDialog = true },
                        onEdit = { goal -> editingGoal = goal; showGoalDialog = true },
                        onDelete = { goal -> deleteGoalTarget = goal }
                    )
                    MainTab.Connect -> ConnectionsPanel(
                        incoming = incomingRequests,
                        outgoing = outgoingRequests,
                        currentUserId = signedIn?.userId ?: "demo-user",
                        demoMode = demoMode,
                        goals = goals,
                        memberProfiles = memberProfiles,
                        onRefresh = {
                            if (demoMode) notice = "Sample content refreshed."
                            else if (signedIn != null) runTask {
                                val pair = api.getConnections(signedIn.userId)
                                incomingRequests = pair.first
                                outgoingRequests = pair.second
                                val otherIds = pair.first.map { it.senderId } + pair.second.map { it.receiverId }
                                memberProfiles = memberProfiles + api.getProfilesByIds(otherIds.distinct())
                            }
                        },
                        onRespond = { requestId, status ->
                            if (signedIn != null && !demoMode) runTask {
                                api.respondToConnection(requestId, status)
                                val pair = api.getConnections(signedIn.userId)
                                incomingRequests = pair.first
                                outgoingRequests = pair.second
                                val otherIds = pair.first.map { it.senderId } + pair.second.map { it.receiverId }
                                memberProfiles = memberProfiles + api.getProfilesByIds(otherIds.distinct())
                                notice = if (status == "accepted") "Connection accepted." else "Connection declined."
                            }
                        }
                    )
                    MainTab.Rooms -> RoomsPanel(
                        rooms = rooms,
                        demoMode = demoMode,
                        onAdd = { showRoomDialog = true },
                        onOpen = { room ->
                            if (demoMode) {
                                selectedRoom = room
                            } else if (signedIn != null) runTask {
                                api.joinRoom(room.id, signedIn.userId)
                                selectedRoom = room
                            }
                        }
                    )
                    MainTab.Progress -> ProgressPanel(
                        rooms = rooms,
                        selectedRoom = selectedProgressRoom,
                        projectItems = progressItems,
                        outcomes = goalOutcomes,
                        invitations = roomInvitations,
                        currentUserId = signedIn?.userId ?: "demo-user",
                        demoMode = demoMode,
                        loading = busy,
                        onSelectRoom = { room ->
                            if (demoMode) {
                                selectedProgressRoom = room
                                progressItems = emptyList()
                                goalOutcomes = emptyList()
                            } else if (signedIn != null) runTask {
                                api.joinRoom(room.id, signedIn.userId)
                                selectedProgressRoom = room
                                progressItems = api.getProjectItems(room.id)
                                goalOutcomes = api.getGoalOutcomes(room.id)
                            }
                        },
                        onBack = { selectedProgressRoom = null },
                        onRefresh = {
                            if (demoMode) notice = "Local progress refreshed."
                            else if (selectedProgressRoom != null) runTask {
                                progressItems = api.getProjectItems(selectedProgressRoom!!.id)
                                goalOutcomes = api.getGoalOutcomes(selectedProgressRoom!!.id)
                            }
                        },
                        onAddItem = { title, description, type ->
                            val room = selectedProgressRoom
                            if (room != null && demoMode) {
                                progressItems = progressItems + ProjectItem(
                                    UUID.randomUUID().toString(), room.id, "demo-user", title, description, type, "open", "Just now", ""
                                )
                                notice = "Sample ${type} added locally."
                            } else if (room != null && signedIn != null) runTask {
                                api.createProjectItem(room.id, signedIn.userId, title, description, type)
                                progressItems = api.getProjectItems(room.id)
                                notice = if (type == "task") "Task added to the room." else "Milestone added to the room."
                            }
                        },
                        onToggleItem = { item ->
                            if (demoMode) {
                                progressItems = progressItems.map {
                                    if (it.id == item.id) it.copy(status = if (it.status == "completed") "open" else "completed") else it
                                }
                            } else runTask {
                                api.setProjectItemStatus(item.id, if (item.status == "completed") "open" else "completed")
                                progressItems = api.getProjectItems(item.roomId)
                                notice = if (item.status == "completed") "Item reopened." else "Progress recorded."
                            }
                        },
                        onAddOutcome = { title, summary ->
                            val room = selectedProgressRoom
                            if (room != null && demoMode) {
                                goalOutcomes = listOf(GoalOutcome(UUID.randomUUID().toString(), room.id, "demo-user", title, summary, "Just now", emptyList())) + goalOutcomes
                                notice = "Sample result recorded locally; it is not a verified public achievement."
                            } else if (room != null && signedIn != null) runTask {
                                api.createGoalOutcome(room.id, signedIn.userId, title, summary)
                                goalOutcomes = api.getGoalOutcomes(room.id)
                                notice = "Result recorded. Teammates can confirm it."
                            }
                        },
                        onConfirmOutcome = { outcome ->
                            if (demoMode) {
                                goalOutcomes = goalOutcomes.map {
                                    if (it.id == outcome.id && "demo-user" !in it.confirmedBy) it.copy(confirmedBy = it.confirmedBy + "demo-user") else it
                                }
                                notice = "Sample confirmation saved locally only."
                            } else if (signedIn != null) runTask {
                                api.confirmGoalOutcome(outcome.id, signedIn.userId)
                                goalOutcomes = api.getGoalOutcomes(outcome.roomId)
                                notice = "You confirmed this collaboration result."
                            }
                        },
                        onInviteMember = { username ->
                            val room = selectedProgressRoom
                            if (demoMode) notice = "Invitations require a real account and online backend."
                            else if (room != null && signedIn != null) runTask {
                                val target = api.getProfileByUsername(username)
                                if (target == null) {
                                    notice = "Member not found. Check the username."
                                } else {
                                    api.inviteRoomMember(room.id, signedIn.userId, target.id)
                                    roomInvitations = api.getRoomInvitations(signedIn.userId)
                                    notice = "Team invitation sent to @${target.username}."
                                }
                            }
                        },
                        onRespondInvitation = { invitation, status ->
                            if (!demoMode && signedIn != null) runTask {
                                api.respondToRoomInvitation(invitation.id, status)
                                roomInvitations = api.getRoomInvitations(signedIn.userId)
                                rooms = api.getRooms()
                                notice = if (status == "accepted") "Invitation accepted. You joined the team room." else "Invitation declined."
                            }
                        }
                    )
                    MainTab.Profile -> ProfilePanel(
                        profile = profile,
                        email = signedIn?.email ?: "demo@humanmesh.local",
                        demoMode = demoMode,
                        loading = busy,
                        onSave = { name, username, bio, skills ->
                            if (demoMode) notice = "Demo profile changes are not linked to a real account."
                            else if (signedIn != null) runTask {
                                val updated = UserProfile(signedIn.userId, name, username, bio, skills.split(",").map { it.trim() }.filter(String::isNotBlank))
                                api.updateProfile(updated)
                                profile = updated
                                notice = "Profile saved."
                            }
                        },
                        onSignOut = {
                            if (demoMode) {
                                demoMode = false
                                selectedProgressRoom = null
                                progressItems = emptyList()
                                goalOutcomes = emptyList()
                                roomInvitations = emptyList()
                                notice = ""
                            } else runTask {
                                api.signOut()
                                current = null
                                profile = null
                                memberProfiles = emptyMap()
                                selectedRoom = null
                                selectedProgressRoom = null
                                messages = emptyList()
                                progressItems = emptyList()
                                goalOutcomes = emptyList()
                                roomInvitations = emptyList()
                                notice = "Signed out."
                            }
                        }
                    )
                    MainTab.Safety -> SafetyPanel(
                        demoMode = demoMode,
                        loading = busy,
                        onBlock = { target ->
                            if (demoMode) notice = "Blocking is unavailable in local demo mode."
                            else if (signedIn != null) runTask {
                                val targetProfile = api.getProfileByUsername(target)
                                if (targetProfile == null) {
                                    notice = "Member not found. Check the username."
                                } else {
                                    api.blockUser(targetProfile.id, signedIn.userId)
                                    notice = "Member blocked. Their profile and public goals should no longer appear."
                                    goals = api.getGoals()
                                    memberProfiles = memberProfiles - targetProfile.id
                                }
                            }
                        },
                        onReport = { target, reason ->
                            if (demoMode) notice = "Reporting is unavailable in local demo mode."
                            else if (signedIn != null) runTask {
                                val targetProfile = api.getProfileByUsername(target)
                                if (targetProfile == null) {
                                    notice = "Member not found. Check the username."
                                } else {
                                    api.reportUser(targetProfile.id, signedIn.userId, reason)
                                    notice = "Report submitted. A moderation team must review it."
                                }
                            }
                        },
                        onDelete = { showDeleteConfirm = true }
                    )
                }
            }
        }
    }

    if (showGoalDialog) {
        GoalDialog(
            initialGoal = editingGoal,
            onDismiss = { showGoalDialog = false; editingGoal = null },
            onCreate = { title, description, category, skills, canOffer, targetTimeline ->
                val priorGoal = editingGoal
                showGoalDialog = false
                editingGoal = null
                if (demoMode) {
                    val goal = if (priorGoal == null) {
                        Goal("demo-" + UUID.randomUUID().toString(), "demo-user", title, description, category, skills, "active", canOffer, targetTimeline)
                    } else {
                        priorGoal.copy(title = title, description = description, category = category, requiredSkills = skills, canOffer = canOffer, targetTimeline = targetTimeline)
                    }
                    ownGoals = if (priorGoal == null) ownGoals + goal else ownGoals.map { if (it.id == goal.id) goal else it }
                    goals = if (priorGoal == null) listOf(goal) + goals else goals.map { if (it.id == goal.id) goal else it }
                    notice = if (priorGoal == null) "Sample goal saved locally." else "Sample goal updated locally."
                } else if (signedIn != null) runTask {
                    if (priorGoal == null) {
                        api.createGoal(signedIn.userId, title, description, category, skills, canOffer, targetTimeline)
                    } else {
                        api.updateGoal(priorGoal.copy(title = title, description = description, category = category, requiredSkills = skills, canOffer = canOffer, targetTimeline = targetTimeline))
                    }
                    ownGoals = api.getGoals(ownOnly = true, ownerId = signedIn.userId)
                    goals = api.getGoals()
                    notice = if (priorGoal == null) "Goal published." else "Goal updated."
                }
            }
        )
    }

    if (deleteGoalTarget != null) {
        val targetGoal = deleteGoalTarget!!
        AlertDialog(
            onDismissRequest = { deleteGoalTarget = null },
            title = { Text("Delete this goal?") },
            text = { Text("\""+targetGoal.title+"\" will be permanently removed. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteGoalTarget = null
                        if (demoMode) {
                            ownGoals = ownGoals.filterNot { it.id == targetGoal.id }
                            goals = goals.filterNot { it.id == targetGoal.id }
                            notice = "Sample goal deleted locally."
                        } else if (signedIn != null) runTask {
                            api.deleteGoal(targetGoal.id, signedIn.userId)
                            ownGoals = api.getGoals(ownOnly = true, ownerId = signedIn.userId)
                            goals = api.getGoals()
                            notice = "Goal deleted."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB93656))
                ) { Text("Delete goal") }
            },
            dismissButton = { TextButton(onClick = { deleteGoalTarget = null }) { Text("Cancel") } }
        )
    }

    if (showRoomDialog) {
        RoomDialog(
            availableGoals = ownGoals,
            onDismiss = { showRoomDialog = false },
            onCreate = { title, description, topic, goalId ->
                showRoomDialog = false
                if (demoMode) {
                    rooms = listOf(OpportunityRoom("demo-" + UUID.randomUUID().toString(), "demo-user", title, description, topic)) + rooms
                    notice = if (goalId != null) "Sample team room created for your goal." else "Sample room created locally."
                } else if (signedIn != null) runTask {
                    api.createRoom(signedIn.userId, title, description, topic, goalId)
                    rooms = api.getRooms()
                    notice = if (goalId != null) "Team room created and linked to your goal." else "Opportunity room created."
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete HumanMesh account?") },
            text = { Text("This permanently deletes your account and associated profile, goals, room membership, and messages according to the configured database relationships. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        if (demoMode) {
                            demoMode = false
                            notice = "Demo mode closed."
                        } else runTask {
                            api.deleteAccount()
                            current = null
                            profile = null
                            selectedRoom = null
                            notice = "Account deleted."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB93656))
                ) { Text("Delete permanently") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}


@Composable
private fun PasswordResetScreen(
    loading: Boolean,
    message: String,
    onClearMessage: () -> Unit,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var showResetPassword by rememberSaveable { mutableStateOf(false) }
    var showConfirmPassword by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF20203A), Color(0xFF10111B))))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Secure your account", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text("Choose a new password. The reset link is time-limited; request another one if it has expired.", color = Color(0xFFB7B7CC), fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(128) },
                label = { Text("New password") },
                visualTransformation = if (showResetPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { showResetPassword = !showResetPassword }) { Icon(if (showResetPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (showResetPassword) "Hide password" else "Show password") } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            PasswordStrengthIndicator(password)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it.take(128) },
                label = { Text("Confirm new password") },
                visualTransformation = if (showConfirmPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { showConfirmPassword = !showConfirmPassword }) { Icon(if (showConfirmPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (showConfirmPassword) "Hide password" else "Show password") } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                NoticeBanner(message, onClearMessage)
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onSubmit(password) },
                enabled = !loading && isStrongPassword(password) && password == confirmPassword,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text(if (loading) "Updating…" else "Update password") }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Back to sign in") }
        }
    }
}

private fun isStrongPassword(password: String): Boolean =
    password.length >= 12 &&
        password.any(Char::isLowerCase) &&
        password.any(Char::isUpperCase) &&
        password.any(Char::isDigit) &&
        password.any { !it.isLetterOrDigit() }

private fun passwordStrength(password: String): Int {
    if (password.isEmpty()) return 0
    var score = 0
    if (password.length >= 8) score++
    if (password.length >= 12) score++
    if (password.any(Char::isLowerCase) && password.any(Char::isUpperCase)) score++
    if (password.any(Char::isDigit)) score++
    if (password.any { !it.isLetterOrDigit() }) score++
    return score
}

@Composable
private fun PasswordStrengthIndicator(password: String) {
    val score = passwordStrength(password)
    val strong = password.length >= 8 && password.any(Char::isLetter) && password.any(Char::isDigit)
    val label = when {
        password.isEmpty() -> "Password strength"
        strong -> "Strong password"
        score >= 3 -> "Almost strong — meet all requirements"
        score >= 1 -> "Weak password"
        else -> "Very weak password"
    }
    val barColor = when {
        password.isEmpty() -> Color(0xFF77798D)
        strong -> Color(0xFF62D8BD)
        score >= 3 -> Color(0xFFFFC857)
        else -> Color(0xFFFF6B7A)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = barColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("$score/5", color = Color(0xFFB9B8CE), fontSize = 11.sp)
        }
        LinearProgressIndicator(
            progress = { score / 5f },
            modifier = Modifier.fillMaxWidth().height(5.dp),
            color = barColor,
            trackColor = Color(0xFF343547)
        )
        Text(
            "Use 12+ characters with uppercase and lowercase letters, a number, and a symbol.",
            color = Color(0xFFB9B8CE),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun AuthScreen(
    configured: Boolean,
    loading: Boolean,
    message: String,
    onClearMessage: () -> Unit,
    onDemo: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
    onForgotPassword: (String) -> Unit,
    pendingVerificationEmail: String?,
    onVerifyEmailCode: (String) -> Unit,
    onResendEmailCode: () -> Unit,
    onCancelEmailVerification: () -> Unit
) {
    var createAccount by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var emailCode by rememberSaveable { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF20203A), Color(0xFF10111B), Color(0xFF10111B))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.humanmesh_logo),
                    contentDescription = "HumanMesh logo",
                    modifier = Modifier.size(58.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("HumanMesh", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text("Better together, by design.", color = Color(0xFFB9B8CE), fontSize = 14.sp)
                }
            }
            if (pendingVerificationEmail != null) {
                Spacer(Modifier.height(28.dp))
                Text("Verify it’s you", fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("Enter the six-digit sign-in code sent to " + pendingVerificationEmail + ". The code expires after 10 minutes.", color = Color(0xFFB9B8CE), fontSize = 14.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(22.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(25.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = emailCode,
                            onValueChange = { emailCode = it.filter(Char::isDigit).take(6) },
                            label = { Text("Six-digit email code") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (message.isNotBlank()) NoticeBanner(message, onClearMessage)
                        Button(
                            onClick = { onVerifyEmailCode(emailCode) },
                            enabled = !loading && emailCode.length == 6,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(15.dp)
                        ) { Text(if (loading) "Verifying…" else "Verify and sign in", fontWeight = FontWeight.Bold) }
                        TextButton(onClick = onResendEmailCode, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                            Text("Send a new code")
                        }
                        TextButton(onClick = onCancelEmailVerification, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel sign-in")
                        }
                    }
                }
                Spacer(Modifier.height(15.dp))
                Text("Never share your verification code with anyone.", color = Color(0xFF8E90A7), fontSize = 12.sp)
            } else {
                Spacer(Modifier.height(26.dp))
                Text("The next step starts with the right people.", fontSize = 32.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(9.dp))
                Text("Share a goal. Find collaborators. Turn ideas into progress.", color = Color(0xFFB9B8CE), fontSize = 15.sp, lineHeight = 23.sp)
                Spacer(Modifier.height(23.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(25.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (createAccount) "Create your account" else "Welcome back", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        if (!configured) {
                            Text("Online sign-in is not enabled in this build yet. Configure SUPABASE_URL and SUPABASE_ANON_KEY in the repository’s Actions secrets to connect a real backend.", color = Color(0xFFFFD39A), fontSize = 13.sp, lineHeight = 19.sp)
                        }
                        if (createAccount) {
                            OutlinedTextField(name, { name = it.take(60) }, label = { Text("Your name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        }
                        OutlinedTextField(email, { email = it.take(200) }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = password, onValueChange = { password = it.take(128) }, label = { Text("Password") }, visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (showPassword) "Hide password" else "Show password") } }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        if (createAccount) PasswordStrengthIndicator(password)
                        if (message.isNotBlank()) NoticeBanner(message, onClearMessage)
                        Button(
                            onClick = {
                                if (createAccount) onSignUp(email.trim(), password, name.trim())
                                else onSignIn(email.trim(), password)
                            },
                            enabled = configured && !loading && email.contains("@") && (if (createAccount) password.length >= 6 else password.isNotBlank()) && (!createAccount || name.trim().length >= 2),
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(15.dp)
                        ) {
                            Text(if (loading) "Please wait…" else if (createAccount) "Create account" else "Sign in", fontWeight = FontWeight.Bold)
                        }
                        if (!createAccount) {
                            TextButton(
                                onClick = { onForgotPassword(email) },
                                enabled = configured && !loading && email.contains("@"),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Forgot password?") }
                        }
                        TextButton(
                            onClick = { createAccount = !createAccount; onClearMessage() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (createAccount) "Already have an account? Sign in" else "New to HumanMesh? Create account")
                        }
                    }
                }
                Spacer(Modifier.height(15.dp))
                OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth().height(49.dp), shape = RoundedCornerShape(15.dp)) {
                    Text("Explore local demo")
                }
                Spacer(Modifier.height(10.dp))
                Text("Your information should be shared only with people you choose. The demo uses sample data only.", color = Color(0xFF8E90A7), fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun ExplorePanel(
    goals: List<Goal>,
    currentUserId: String,
    mySkills: List<String>,
    ownerProfiles: Map<String, UserProfile>,
    people: List<UserProfile>,
    demoMode: Boolean,
    onConnect: (Goal) -> Unit,
    onRefresh: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showingPeople by rememberSaveable { mutableStateOf(false) }
    val visiblePeople = remember(people, mySkills, query, currentUserId) {
        people.filter { person ->
            person.id != currentUserId &&
                (query.isBlank() ||
                    person.displayName.contains(query, ignoreCase = true) ||
                    person.username.contains(query, ignoreCase = true) ||
                    person.bio.contains(query, ignoreCase = true) ||
                    person.skills.any { it.contains(query, ignoreCase = true) })
        }.sortedWith(
            compareByDescending<UserProfile> { SkillMatcher.score(it.skills, mySkills) ?: -1 }
                .thenBy { it.displayName.lowercase() }
        )
    }
    val visibleGoals = remember(goals, mySkills, query) {
        goals
            .filter { goal ->
                query.isBlank() ||
                    goal.title.contains(query, ignoreCase = true) ||
                    goal.description.contains(query, ignoreCase = true) ||
                    goal.category.contains(query, ignoreCase = true) ||
                    goal.requiredSkills.any { it.contains(query, ignoreCase = true) }
            }
            .sortedWith(
                compareByDescending<Goal> { SkillMatcher.score(it.requiredSkills, mySkills) ?: -1 }
                    .thenByDescending { it.status == "active" }
            )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            Column {
                Text("Find your people", color = Color(0xFF9B8AFB), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(5.dp))
                Text("Big goals are better shared.", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 33.sp)
                Spacer(Modifier.height(7.dp))
                Text("Discover goals you can help move forward.", color = Color(0xFFB7B7CC), fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (showingPeople) {
                        OutlinedButton(onClick = { showingPeople = false; query = "" }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Goals") }
                    } else {
                        Button(onClick = { showingPeople = false; query = "" }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Goals") }
                    }
                    if (showingPeople) {
                        Button(onClick = { showingPeople = true; query = "" }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("People") }
                    } else {
                        OutlinedButton(onClick = { showingPeople = true; query = "" }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("People") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (showingPeople) "Search people and skills" else "Search goals, topics, and skills") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                if (mySkills.isEmpty() && !demoMode) {
                    Spacer(Modifier.height(4.dp))
                    Text("Add skills in your Profile to see skill-fit ranking.", color = Color(0xFF8E90A7), fontSize = 11.sp)
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (demoMode) "SAMPLE GOALS" else "PUBLIC GOALS", color = Color(0xFF6BE0C1), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onRefresh) { Text("Refresh") }
                }
            }
        }
        if (showingPeople) {
            if (visiblePeople.isEmpty()) {
                item {
                    EmptyState(
                        title = if (query.isBlank()) "No member profiles yet" else "No people found",
                        subtitle = if (query.isNotBlank()) "Try another name, username, or skill." else "People who create a profile will appear here."
                    )
                }
            } else {
                items(visiblePeople, key = { it.id }) { person ->
                    val openGoal = goals.firstOrNull { it.ownerId == person.id && it.status == "active" }
                    PersonCard(
                        profile = person,
                        skillFitPercent = SkillMatcher.score(person.skills, mySkills),
                        hasOpenGoal = openGoal != null,
                        onConnect = { if (openGoal != null) onConnect(openGoal) }
                    )
                }
            }
        } else if (visibleGoals.isEmpty()) {
            item {
                EmptyState(
                    title = if (query.isBlank()) "Nothing here yet" else "No goals found",
                    subtitle = if (query.isNotBlank()) "Try another topic or skill." else if (demoMode) "Use My goals to add a sample goal." else "Be one of the first to publish a goal that needs collaborators."
                )
            }
        } else {
            items(visibleGoals, key = { it.id }) { goal ->
                GoalCard(
                    goal = goal,
                    ownGoal = goal.ownerId == currentUserId || (demoMode && goal.ownerId == "demo-user"),
                    demoMode = demoMode,
                    onConnect = { onConnect(goal) },
                    skillFitPercent = SkillMatcher.score(goal.requiredSkills, mySkills),
                    ownerProfile = ownerProfiles[goal.ownerId],
                    matchReasons = SkillMatcher.reasons(goal.requiredSkills, mySkills)
                )
            }
        }
    }
}

@Composable
private fun PersonCard(
    profile: UserProfile,
    skillFitPercent: Int?,
    hasOpenGoal: Boolean,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).background(
                        Brush.linearGradient(listOf(Color(0xFF9B8AFB), Color(0xFF62D8BD))),
                        CircleShape
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(profile.displayName.take(1).uppercase(), color = Color(0xFF10111B), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.displayName, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("@${profile.username}", color = Color(0xFF9B8AFB), fontSize = 11.sp)
                }
                if (skillFitPercent != null && skillFitPercent > 0) {
                    Text("${skillFitPercent}% fit", color = Color(0xFF6BE0C1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (profile.bio.isNotBlank()) {
                Text(profile.bio, color = Color(0xFFB7B7CC), fontSize = 13.sp, lineHeight = 20.sp)
            }
            if (profile.skills.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    profile.skills.take(4).forEach { skill ->
                        Surface(color = Color(0xFF292B40), shape = RoundedCornerShape(9.dp)) {
                            Text(skill, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = Color(0xFFD8D5F7), fontSize = 10.sp)
                        }
                    }
                }
            }
            Button(
                onClick = onConnect,
                enabled = hasOpenGoal,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) { Text(if (hasOpenGoal) "Request collaboration" else "No open public goal") }
        }
    }
}

@Composable
private fun GoalCard(
    goal: Goal,
    ownGoal: Boolean,
    demoMode: Boolean,
    onConnect: () -> Unit,
    skillFitPercent: Int? = null,
    ownerProfile: UserProfile? = null,
    matchReasons: List<String> = emptyList(),
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(goal.category.uppercase(), color = Color(0xFF6BE0C1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Surface(color = Color(0xFF26283B), shape = CircleShape) {
                    Text("OPEN GOAL", color = Color(0xFFBBB6F5), fontSize = 9.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Text(goal.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp)
            if (ownerProfile != null) {
                Text("by ${ownerProfile.displayName} · @${ownerProfile.username}", color = Color(0xFF8E90A7), fontSize = 11.sp)
            }
            if (skillFitPercent != null && goal.requiredSkills.isNotEmpty() && skillFitPercent > 0) {
                Text(
                    "SKILL OVERLAP · $skillFitPercent%",
                    color = Color(0xFF6BE0C1),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(goal.description.ifBlank { "A new opportunity to bring good people together." }, color = Color(0xFFB7B7CC), fontSize = 13.sp, lineHeight = 20.sp)
            if (goal.canOffer.isNotBlank()) {
                Text("CAN CONTRIBUTE · ${goal.canOffer}", color = Color(0xFFB7B7CC), fontSize = 12.sp, lineHeight = 18.sp)
            }
            if (goal.targetTimeline.isNotBlank()) {
                Text("TARGET TIMELINE · ${goal.targetTimeline}", color = Color(0xFFB7B7CC), fontSize = 11.sp)
            }
            if (matchReasons.isNotEmpty() && !ownGoal) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("WHY THIS MATCH", color = Color(0xFF9B8AFB), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    matchReasons.take(2).forEach { reason ->
                        Text("• $reason", color = Color(0xFFB7B7CC), fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
            if (goal.requiredSkills.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    goal.requiredSkills.take(3).forEach { skill ->
                        Surface(color = Color(0xFF292B40), shape = RoundedCornerShape(10.dp)) {
                            Text(skill, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = Color(0xFFD8D5F7), fontSize = 11.sp)
                        }
                    }
                }
            }
            Button(
                onClick = onConnect,
                enabled = !ownGoal,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(13.dp)
            ) {
                Text(if (ownGoal) "Your goal" else if (demoMode) "Preview collaboration" else "Request to connect")
            }
            if (onEdit != null || onDelete != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (onEdit != null) OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Edit goal") }
                    if (onDelete != null) OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9DAA)), shape = RoundedCornerShape(12.dp)) { Text("Delete goal") }
                }
            }
        }
    }
}

@Composable
private fun MyGoalsPanel(
    goals: List<Goal>,
    demoMode: Boolean,
    onAdd: () -> Unit,
    onEdit: (Goal) -> Unit,
    onDelete: (Goal) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
            Text("Your direction", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text("Goals make it easier for the right people to find you.", color = Color(0xFFB7B7CC), fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                Text("+  Create a goal", fontWeight = FontWeight.Bold)
            }
        }
        if (goals.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.TopCenter) {
                EmptyState("What are you working toward?", "Post a clear goal and describe the skills or collaborators you need.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(goals, key = { it.id }) { goal ->
                    GoalCard(goal, ownGoal = true, demoMode = demoMode, onConnect = {}, onEdit = { onEdit(goal) }, onDelete = { onDelete(goal) })
                }
            }
        }
    }
}

@Composable
private fun ConnectionsPanel(
    incoming: List<MatchRequest>,
    outgoing: List<MatchRequest>,
    currentUserId: String,
    demoMode: Boolean,
    goals: List<Goal>,
    memberProfiles: Map<String, UserProfile>,
    onRefresh: () -> Unit,
    onRespond: (String, String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Connections", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text("Requests are private to their participants. Accept only people you want to work with.", color = Color(0xFFB7B7CC), fontSize = 13.sp, lineHeight = 20.sp)
            TextButton(onClick = onRefresh) { Text("Refresh requests") }
        }
        item {
            Text("INCOMING", color = Color(0xFF6BE0C1), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
        if (incoming.isEmpty()) {
            item { Text(if (demoMode) "Sample mode has no real connection requests." else "No pending requests right now.", color = Color(0xFFB7B7CC), fontSize = 13.sp) }
        } else {
            items(incoming, key = { "in-" + it.id }) { item ->
                ConnectionRequestCard(
                    request = item,
                    partnerId = item.senderId,
                    partnerProfile = memberProfiles[item.senderId],
                    goalTitle = goals.firstOrNull { goal -> goal.id == item.goalId }?.title ?: "Goal collaboration",
                    incoming = true,
                    onRespond = onRespond
                )
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text("SENT REQUESTS", color = Color(0xFF6BE0C1), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
        if (outgoing.isEmpty()) {
            item { Text("You have not sent any requests yet.", color = Color(0xFFB7B7CC), fontSize = 13.sp) }
        } else {
            items(outgoing, key = { "out-" + it.id }) { item ->
                ConnectionRequestCard(
                    request = item,
                    partnerId = item.receiverId,
                    partnerProfile = memberProfiles[item.receiverId],
                    goalTitle = goals.firstOrNull { goal -> goal.id == item.goalId }?.title ?: "Goal collaboration",
                    incoming = false,
                    onRespond = onRespond
                )
            }
        }
    }
}

@Composable
private fun ConnectionRequestCard(
    request: MatchRequest,
    partnerId: String,
    partnerProfile: UserProfile?,
    goalTitle: String,
    incoming: Boolean,
    onRespond: (String, String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                (if (incoming) "FROM " else "TO ") + (partnerProfile?.displayName ?: ("Member " + partnerId.take(8))) +
                    (partnerProfile?.username?.let { " · @$it" } ?: ""),
                color = Color(0xFF9B8AFB),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(goalTitle, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(request.message, color = Color(0xFFB7B7CC), fontSize = 13.sp, lineHeight = 19.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Status: " + request.status, color = Color(0xFF6BE0C1), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (incoming && request.status == "pending") {
                    TextButton(onClick = { onRespond(request.id, "rejected") }) { Text("Decline") }
                    Button(onClick = { onRespond(request.id, "accepted") }, shape = RoundedCornerShape(10.dp)) { Text("Accept") }
                }
            }
        }
    }
}

@Composable
private fun RoomsPanel(
    rooms: List<OpportunityRoom>,
    demoMode: Boolean,
    onAdd: () -> Unit,
    onOpen: (OpportunityRoom) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
            Text("Opportunity rooms", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text("Small spaces for people to plan, learn, and build together.", color = Color(0xFFB7B7CC), fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(14.dp))
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                Text("+  Start a room", fontWeight = FontWeight.Bold)
            }
        }
        if (rooms.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.TopCenter) {
                EmptyState("Make the first room", "Choose a topic and invite people who want to make progress.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(rooms, key = { it.id }) { room ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))
                    ) {
                        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(room.topic.uppercase(), color = Color(0xFF6BE0C1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(room.title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(room.description.ifBlank { "An open space to exchange ideas and organize next steps." }, color = Color(0xFFB7B7CC), fontSize = 13.sp, lineHeight = 20.sp)
                            Button(onClick = { onOpen(room) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                Text(if (demoMode) "Enter sample room" else "Join and open room")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomChatPanel(
    room: OpportunityRoom,
    messages: List<RoomMessage>,
    demoMode: Boolean,
    currentUserId: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSend: (String) -> Unit
) {
    var draft by rememberSaveable(room.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Rooms") }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(room.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (demoMode) "Sample conversation" else "Members-only messages", color = Color(0xFF6BE0C1), fontSize = 11.sp)
            }
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) item { Text("Start the conversation with a useful next step.", color = Color(0xFFB7B7CC)) }
            items(messages, key = { it.id }) { message ->
                val mine = message.senderId == currentUserId || (demoMode && message.senderId == "demo-user")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (mine) Color(0xFF353051) else Color(0xFF191B29),
                        shape = RoundedCornerShape(17.dp),
                        modifier = Modifier.fillMaxWidth(0.88f)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (mine) "You" else "Room member", color = if (mine) Color(0xFFB8ADF9) else Color(0xFF6BE0C1), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(message.body, fontSize = 14.sp, lineHeight = 20.sp)
                            Text(message.createdAt.take(16), color = Color(0xFF8E90A7), fontSize = 9.sp)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(2000) },
                modifier = Modifier.weight(1f),
                label = { Text("Message") },
                maxLines = 3
            )
            Button(
                onClick = { val text = draft.trim(); if (text.isNotEmpty()) { onSend(text); draft = "" } },
                enabled = draft.isNotBlank(),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Send") }
        }
    }
}

@Composable
private fun ProfilePanel(
    profile: UserProfile?,
    email: String,
    demoMode: Boolean,
    loading: Boolean,
    onSave: (String, String, String, String) -> Unit,
    onSignOut: () -> Unit
) {
    var name by rememberSaveable(profile?.id) { mutableStateOf(profile?.displayName.orEmpty()) }
    var username by rememberSaveable(profile?.id) { mutableStateOf(profile?.username.orEmpty()) }
    var bio by rememberSaveable(profile?.id) { mutableStateOf(profile?.bio.orEmpty()) }
    var skills by rememberSaveable(profile?.id) { mutableStateOf(profile?.skills?.joinToString(", ").orEmpty()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Text("Your profile", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text("Help collaborators understand what you bring and what you want to build.", color = Color(0xFFB7B7CC), fontSize = 14.sp, lineHeight = 21.sp)
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(66.dp).background(Brush.linearGradient(listOf(Color(0xFF9B8AFB), Color(0xFF62D8BD))), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(name.trim().take(1).ifBlank { "H" }.uppercase(), color = Color(0xFF10111B), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text(email, color = Color(0xFFB7B7CC), fontSize = 12.sp)
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(username, { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(30) }, label = { Text("Username (letters, numbers, underscore)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(bio, { bio = it.take(400) }, label = { Text("About you") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5)
                OutlinedTextField(skills, { skills = it.take(500) }, label = { Text("Skills, separated by commas") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(
                    onClick = { onSave(name, username, bio, skills) },
                    enabled = !loading && name.trim().length >= 2 && username.length >= 3,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(13.dp)
                ) { Text("Save profile") }
            }
        }
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
            Text(if (demoMode) "Exit demo" else "Sign out")
        }
    }
}

@Composable
private fun SafetyPanel(
    demoMode: Boolean,
    loading: Boolean,
    onBlock: (String) -> Unit,
    onReport: (String, String) -> Unit,
    onDelete: () -> Unit
) {
    var target by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Text("Safety & control", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text("You decide who you interact with. Blocking and reporting require an account and a connected backend.", color = Color(0xFFB7B7CC), fontSize = 14.sp, lineHeight = 21.sp)
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("Block or report a member", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Enter the member's public username, with or without @. Only visible profiles can be found.", color = Color(0xFFB7B7CC), fontSize = 12.sp, lineHeight = 18.sp)
                OutlinedTextField(target, { target = it.removePrefix("@").lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(30) }, label = { Text("Member username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(reason, { reason = it.take(1000) }, label = { Text("Report reason") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedButton(
                    onClick = { onBlock(target.trim()) },
                    enabled = !loading && target.isNotBlank() && !demoMode,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Block member") }
                Button(
                    onClick = { onReport(target.trim(), reason.trim()) },
                    enabled = !loading && target.isNotBlank() && reason.trim().length >= 3 && !demoMode,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Submit report") }
                if (demoMode) Text("Demo mode: safety actions are intentionally disabled.", color = Color(0xFFFFD39A), fontSize = 12.sp)
            }
        }
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Account control", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Account deletion removes the authentication account through a server-side function. The function must be deployed before the action can work.", color = Color(0xFFB7B7CC), fontSize = 12.sp, lineHeight = 18.sp)
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9DAA)),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (demoMode) "Exit demo" else "Delete my account") }
            }
        }
        Text("Security note: no software can be guaranteed completely secure. Real-user launch requires policy tests, moderation operations, monitoring, and independent review.", color = Color(0xFF8E90A7), fontSize = 12.sp, lineHeight = 18.sp)
    }
}


@Composable
private fun ProgressPanel(
    rooms: List<OpportunityRoom>,
    selectedRoom: OpportunityRoom?,
    projectItems: List<ProjectItem>,
    outcomes: List<GoalOutcome>,
    invitations: List<RoomInvitation>,
    currentUserId: String,
    demoMode: Boolean,
    loading: Boolean,
    onSelectRoom: (OpportunityRoom) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAddItem: (String, String, String) -> Unit,
    onToggleItem: (ProjectItem) -> Unit,
    onAddOutcome: (String, String) -> Unit,
    onConfirmOutcome: (GoalOutcome) -> Unit,
    onInviteMember: (String) -> Unit,
    onRespondInvitation: (RoomInvitation, String) -> Unit
) {
    var itemTitle by rememberSaveable(selectedRoom?.id) { mutableStateOf("") }
    var itemDescription by rememberSaveable(selectedRoom?.id) { mutableStateOf("") }
    var outcomeTitle by rememberSaveable(selectedRoom?.id) { mutableStateOf("") }
    var outcomeSummary by rememberSaveable(selectedRoom?.id) { mutableStateOf("") }
    var inviteUsername by rememberSaveable(selectedRoom?.id) { mutableStateOf("") }
    if (selectedRoom == null) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Progress & results", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text("Turn collaboration into visible progress. Tasks, milestones and results are shared with room members.", color = Color(0xFFB7B7CC), fontSize = 14.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(5.dp))
                Text("TEAM INVITATIONS", color = Color(0xFF6BE0C1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            val incomingInvitations = invitations.filter { it.inviteeId == currentUserId && it.status == "pending" }
            if (incomingInvitations.isEmpty()) {
                item { Text("No pending team invitations.", color = Color(0xFFB7B7CC), fontSize = 12.sp) }
            } else {
                items(incomingInvitations, key = { "inv-" + it.id }) { invitation ->
                    val roomTitle = rooms.firstOrNull { it.id == invitation.roomId }?.title ?: "Team room"
                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))) {
                        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(roomTitle, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Invited you to join this collaboration room.", color = Color(0xFFB7B7CC), fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onRespondInvitation(invitation, "rejected") }, modifier = Modifier.weight(1f)) { Text("Decline") }
                                Button(onClick = { onRespondInvitation(invitation, "accepted") }, modifier = Modifier.weight(1f)) { Text("Accept") }
                            }
                        }
                    }
                }
            }
            item { Text("SELECT A TEAM ROOM", color = Color(0xFF6BE0C1), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            if (rooms.isEmpty()) {
                item { EmptyState("Create your first team room", "Open Rooms, start a room, and link it to one of your goals. Then manage tasks and results here.") }
            } else {
                items(rooms, key = { "progress-" + it.id }) { room ->
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(room.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(room.description, color = Color(0xFFB7B7CC), fontSize = 13.sp, lineHeight = 19.sp)
                            Text(room.topic.uppercase(), color = Color(0xFF6BE0C1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { onSelectRoom(room) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Open team progress") }
                        }
                    }
                }
            }
            item {
                Text("Reputation is earned through work: completed tasks and outcomes confirmed by teammates—not likes or follower counts.", color = Color(0xFF8E90A7), fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack) { Text("← All team rooms") }
            Text("Team progress", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Text(selectedRoom.title, color = Color(0xFF6BE0C1), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Tasks completed: ${projectItems.count { it.status == "completed" }} / ${projectItems.size}  ·  Milestones completed: ${projectItems.count { it.itemType == "milestone" && it.status == "completed" }}", color = Color(0xFFB7B7CC), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.weight(1f)) { Text("Refresh") }
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Switch room") }
            }
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Invite a collaborator", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Invite a HumanMesh member by username. They must accept before they become a room member.", color = Color(0xFFB7B7CC), fontSize = 12.sp, lineHeight = 18.sp)
                    OutlinedTextField(inviteUsername, { inviteUsername = it.removePrefix("@").lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(30) }, label = { Text("Member username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = { onInviteMember(inviteUsername); inviteUsername = "" }, enabled = !loading && !demoMode && inviteUsername.length >= 3, modifier = Modifier.fillMaxWidth()) { Text("Send team invitation") }
                }
            }
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Add a task or milestone", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(itemTitle, { itemTitle = it.take(120) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(itemDescription, { itemDescription = it.take(1000) }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onAddItem(itemTitle.trim(), itemDescription.trim(), "task"); itemTitle = ""; itemDescription = "" },
                            enabled = !loading && itemTitle.trim().length >= 3,
                            modifier = Modifier.weight(1f)
                        ) { Text("Add task") }
                        OutlinedButton(
                            onClick = { onAddItem(itemTitle.trim(), itemDescription.trim(), "milestone"); itemTitle = ""; itemDescription = "" },
                            enabled = !loading && itemTitle.trim().length >= 3,
                            modifier = Modifier.weight(1f)
                        ) { Text("Add milestone") }
                    }
                }
            }
            Text("TASKS & MILESTONES", color = Color(0xFF6BE0C1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            if (projectItems.isEmpty()) Text("No tasks yet. Add the first clear next step.", color = Color(0xFFB7B7CC), fontSize = 13.sp)
            projectItems.forEach { item ->
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))) {
                    Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.itemType.uppercase() + if (item.status == "completed") " · COMPLETED" else " · OPEN", color = if (item.status == "completed") Color(0xFF6BE0C1) else Color(0xFF9B8AFB), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(item.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        if (item.description.isNotBlank()) Text(item.description, color = Color(0xFFB7B7CC), fontSize = 12.sp, lineHeight = 18.sp)
                        OutlinedButton(onClick = { onToggleItem(item) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                            Text(if (item.status == "completed") "Reopen item" else "Mark complete")
                        }
                    }
                }
            }
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Record a result", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Describe what the team actually achieved. Other room members can confirm the result; confirmations are not a guarantee that every claim is objectively true.", color = Color(0xFFB7B7CC), fontSize = 12.sp, lineHeight = 18.sp)
                    OutlinedTextField(outcomeTitle, { outcomeTitle = it.take(120) }, label = { Text("Result title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(outcomeSummary, { outcomeSummary = it.take(1500) }, label = { Text("What was achieved? (10+ characters)") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    Button(
                        onClick = { onAddOutcome(outcomeTitle.trim(), outcomeSummary.trim()); outcomeTitle = ""; outcomeSummary = "" },
                        enabled = !loading && outcomeTitle.trim().length >= 3 && outcomeSummary.trim().length >= 10,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Record result") }
                }
            }
            Text("COLLABORATION RESULTS", color = Color(0xFF6BE0C1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            if (outcomes.isEmpty()) Text("No results recorded for this room yet.", color = Color(0xFFB7B7CC), fontSize = 13.sp)
            outcomes.forEach { outcome ->
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))) {
                    Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(outcome.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(outcome.summary, color = Color(0xFFB7B7CC), fontSize = 13.sp, lineHeight = 19.sp)
                        Text("Teammate confirmations: ${outcome.confirmationCount}", color = Color(0xFF6BE0C1), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (outcome.authorId != currentUserId && !outcome.isConfirmedBy(currentUserId)) {
                            OutlinedButton(onClick = { onConfirmOutcome(outcome) }, enabled = !loading && !demoMode, modifier = Modifier.fillMaxWidth()) { Text("Confirm this result") }
                        } else if (outcome.isConfirmedBy(currentUserId)) {
                            Text("You confirmed this result", color = Color(0xFF8E90A7), fontSize = 11.sp)
                        }
                    }
                }
            }
            if (demoMode) Text("Demo progress is stored only in memory and is not a real public achievement.", color = Color(0xFFFFD39A), fontSize = 12.sp)
        }
    }
}

@Composable
private fun GoalDialog(
    initialGoal: Goal?,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, List<String>, String, String) -> Unit
) {
    var title by rememberSaveable(initialGoal?.id) { mutableStateOf(initialGoal?.title.orEmpty()) }
    var description by rememberSaveable(initialGoal?.id) { mutableStateOf(initialGoal?.description.orEmpty()) }
    var category by rememberSaveable(initialGoal?.id) { mutableStateOf(initialGoal?.category ?: "Product building") }
    var skills by rememberSaveable(initialGoal?.id) { mutableStateOf(initialGoal?.requiredSkills?.joinToString(", ").orEmpty()) }
    var canOffer by rememberSaveable(initialGoal?.id) { mutableStateOf(initialGoal?.canOffer.orEmpty()) }
    var targetTimeline by rememberSaveable(initialGoal?.id) { mutableStateOf(initialGoal?.targetTimeline.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialGoal == null) "Publish a goal" else "Edit goal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it.take(100) }, label = { Text("Goal title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(2000) }, label = { Text("What do you want to achieve?") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(category, { category = it.take(50) }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(skills, { skills = it.take(300) }, label = { Text("Skills needed, comma-separated") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(canOffer, { canOffer = it.take(1000) }, label = { Text("What can you contribute?") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(targetTimeline, { targetTimeline = it.take(100) }, label = { Text("Target timeline (e.g. 3 months)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        title.trim(),
                        description.trim(),
                        category.trim(),
                        skills.split(",").map { it.trim() }.filter(String::isNotBlank).take(20),
                        canOffer.trim(),
                        targetTimeline.trim()
                    )
                },
                enabled = title.trim().length >= 3
            ) { Text(if (initialGoal == null) "Publish" else "Save changes") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RoomDialog(
    availableGoals: List<Goal>,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String?) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("Collaboration") }
    var selectedGoalId by rememberSaveable { mutableStateOf("") }
    var goalsExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a team room") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it.take(100) }, label = { Text("Room title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(1500) }, label = { Text("What will the team work on?") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(topic, { topic = it.take(50) }, label = { Text("Topic") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Box {
                    OutlinedButton(onClick = { goalsExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(availableGoals.firstOrNull { it.id == selectedGoalId }?.title ?: "Link to a goal (optional)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = goalsExpanded, onDismissRequest = { goalsExpanded = false }) {
                        DropdownMenuItem(text = { Text("No linked goal") }, onClick = { selectedGoalId = ""; goalsExpanded = false })
                        availableGoals.forEach { goal ->
                            DropdownMenuItem(
                                text = { Text(goal.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = { selectedGoalId = goal.id; goalsExpanded = false }
                            )
                        }
                    }
                }
                if (availableGoals.isEmpty()) {
                    Text("Create a goal first if you want to link this room to a project.", color = Color(0xFFB7B7CC), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(title.trim(), description.trim(), topic.trim(), selectedGoalId.ifBlank { null }) },
                enabled = title.trim().length >= 3
            ) { Text("Create team room") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF191B29))
    ) {
        Column(Modifier.fillMaxWidth().padding(23.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("✧", fontSize = 35.sp, color = Color(0xFF9B8AFB))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(subtitle, color = Color(0xFFB7B7CC), fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        color = Color(0xFF282642),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), color = Color(0xFFE9E6FF), fontSize = 12.sp, lineHeight = 17.sp)
            TextButton(onClick = onDismiss) { Text("OK", fontSize = 11.sp) }
        }
    }
}


private fun demoProfiles(): Map<String, UserProfile> = mapOf(
    "sample-person-1" to UserProfile("sample-person-1", "Amina", "amina_builds", "Creating tools that help people learn.", listOf("Android", "UX research", "Education")),
    "sample-person-2" to UserProfile("sample-person-2", "Bilal", "bilal_creates", "Interested in small-business products and community design.", listOf("Design", "Marketing", "Research")),
    "sample-person-3" to UserProfile("sample-person-3", "Sara", "sara_codes", "Learning by building small projects with others.", listOf("Mentoring", "Kotlin", "Planning"))
)

private fun demoGoals(): List<Goal> = listOf(
    Goal("demo-g1", "sample-person-1", "Build a useful learning app", "Looking for someone who enjoys UX, Android development, and testing small ideas with real learners.", "Product building", listOf("Android", "UX research", "Education"), "active"),
    Goal("demo-g2", "sample-person-2", "Make local businesses easier to find", "Explore a community-first directory with trustworthy listings and simple tools for small businesses.", "Community", listOf("Design", "Marketing", "Research"), "active"),
    Goal("demo-g3", "sample-person-3", "Launch a beginner coding circle", "Create a friendly weekly room where beginners build one small project at a time.", "Learning", listOf("Mentoring", "Kotlin", "Planning"), "active")
)

private fun demoRooms(): List<OpportunityRoom> = listOf(
    OpportunityRoom("demo-room-1", "sample-person-1", "Build in public", "Share progress, ask practical questions, and help each other remove blockers.", "Product building"),
    OpportunityRoom("demo-room-2", "sample-person-2", "Learn by shipping", "A low-pressure space for small projects, feedback, and consistent practice.", "Learning")
)

package com.humanmesh.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humanmesh.android.data.Goal
import com.humanmesh.android.data.HumanMeshApi
import com.humanmesh.android.data.OpportunityRoom
import com.humanmesh.android.data.RoomMessage
import com.humanmesh.android.data.SessionStore
import com.humanmesh.android.data.UserProfile
import com.humanmesh.android.data.UserSession
import com.humanmesh.android.ui.HumanMeshTheme
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HumanMeshTheme { HumanMeshApp() } }
    }
}

private enum class MainTab(val label: String, val symbol: String) {
    Explore("Explore", "✦"),
    Goals("My goals", "◎"),
    Rooms("Rooms", "◉"),
    Profile("Profile", "○"),
    Safety("Safety", "盾")
}

@Composable
private fun HumanMeshApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessions = remember { SessionStore(context) }
    val api = remember { HumanMeshApi(sessions) }
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf(sessions.read()) }
    var demoMode by rememberSaveable { mutableStateOf(false) }
    var activeTab by rememberSaveable { mutableStateOf(MainTab.Explore) }
    var goals by remember { mutableStateOf(demoGoals()) }
    var ownGoals by remember { mutableStateOf(emptyList<Goal>()) }
    var rooms by remember { mutableStateOf(demoRooms()) }
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var selectedRoom by remember { mutableStateOf<OpportunityRoom?>(null) }
    var messages by remember { mutableStateOf(emptyList<RoomMessage>()) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showGoalDialog by remember { mutableStateOf(false) }
    var showRoomDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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

    LaunchedEffect(current?.userId, activeTab, selectedRoom?.id, demoMode) {
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
                if (selectedRoom != null) messages = api.getMessages(selectedRoom!!.id)
            }.onFailure {
                notice = it.message?.take(180) ?: "Could not refresh HumanMesh."
            }
        }
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
                notice = "Demo mode uses sample content only. No real accounts or other people are connected."
            },
            onSignIn = { email, password ->
                runTask {
                    val session = api.signIn(email, password)
                    api.ensureProfile(session, email.substringBefore("@").ifBlank { "New member" })
                    current = session
                    demoMode = false
                    activeTab = MainTab.Explore
                    notice = "Welcome back."
                }
            },
            onSignUp = { email, password, name ->
                runTask {
                    val session = api.signUp(email, password)
                    if (session == null) {
                        notice = "Account request sent. Verify your email, then return here to sign in."
                    } else {
                        api.ensureProfile(session, name)
                        current = session
                        demoMode = false
                        activeTab = MainTab.Explore
                        notice = "Your HumanMesh account is ready."
                    }
                }
            }
        )
        return
    }

    val signedIn = current
    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        containerColor = Color(0xFF10111B),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFF9B8AFB), Color(0xFF62D8BD))),
                                    RoundedCornerShape(13.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("H", color = Color(0xFF10111B), fontWeight = FontWeight.Black, fontSize = 21.sp)
                        }
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
                            else runTask { goals = api.getGoals() }
                        }
                    )
                    MainTab.Goals -> MyGoalsPanel(
                        goals = if (demoMode) ownGoals else ownGoals,
                        demoMode = demoMode,
                        onAdd = { showGoalDialog = true }
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
                                notice = ""
                            } else runTask {
                                api.signOut()
                                current = null
                                profile = null
                                selectedRoom = null
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
                                api.blockUser(target, signedIn.userId)
                                notice = "User blocked. Their public profile and goals will be hidden by the database rules."
                                goals = api.getGoals()
                            }
                        },
                        onReport = { target, reason ->
                            if (demoMode) notice = "Reporting is unavailable in local demo mode."
                            else if (signedIn != null) runTask {
                                api.reportUser(target, signedIn.userId, reason)
                                notice = "Report submitted. Reports require a human review process to be configured."
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
            onDismiss = { showGoalDialog = false },
            onCreate = { title, description, category, skills ->
                showGoalDialog = false
                if (demoMode) {
                    val goal = Goal("demo-" + UUID.randomUUID().toString(), "demo-user", title, description, category, skills, "active")
                    ownGoals = ownGoals + goal
                    goals = listOf(goal) + goals
                    notice = "Sample goal saved locally in demo mode."
                } else if (signedIn != null) runTask {
                    api.createGoal(signedIn.userId, title, description, category, skills)
                    ownGoals = api.getGoals(ownOnly = true, ownerId = signedIn.userId)
                    goals = api.getGoals()
                    notice = "Goal published."
                }
            }
        )
    }

    if (showRoomDialog) {
        RoomDialog(
            onDismiss = { showRoomDialog = false },
            onCreate = { title, description, topic ->
                showRoomDialog = false
                if (demoMode) {
                    rooms = listOf(OpportunityRoom("demo-" + UUID.randomUUID().toString(), "demo-user", title, description, topic)) + rooms
                    notice = "Sample room created locally."
                } else if (signedIn != null) runTask {
                    api.createRoom(signedIn.userId, title, description, topic)
                    rooms = api.getRooms()
                    notice = "Opportunity room created."
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
private fun AuthScreen(
    configured: Boolean,
    loading: Boolean,
    message: String,
    onClearMessage: () -> Unit,
    onDemo: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit
) {
    var createAccount by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }

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
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .background(Brush.linearGradient(listOf(Color(0xFF9B8AFB), Color(0xFF62D8BD))), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("H", color = Color(0xFF10111B), fontSize = 31.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("HumanMesh", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text("Better together, by design.", color = Color(0xFFB9B8CE), fontSize = 14.sp)
                }
            }
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
                    OutlinedTextField(password, { password = it.take(128) }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (message.isNotBlank()) NoticeBanner(message, onClearMessage)
                    Button(
                        onClick = {
                            if (createAccount) onSignUp(email.trim(), password, name.trim())
                            else onSignIn(email.trim(), password)
                        },
                        enabled = configured && !loading && email.contains("@") && password.length >= 8 && (!createAccount || name.trim().length >= 2),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Text(if (loading) "Please wait…" else if (createAccount) "Create account" else "Sign in", fontWeight = FontWeight.Bold)
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

@Composable
private fun ExplorePanel(
    goals: List<Goal>,
    currentUserId: String,
    demoMode: Boolean,
    onConnect: (Goal) -> Unit,
    onRefresh: () -> Unit
) {
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
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (demoMode) "SAMPLE GOALS" else "PUBLIC GOALS", color = Color(0xFF6BE0C1), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onRefresh) { Text("Refresh") }
                }
            }
        }
        if (goals.isEmpty()) {
            item {
                EmptyState(
                    title = "Nothing here yet",
                    subtitle = if (demoMode) "Use My goals to add a sample goal." else "Be one of the first to publish a goal that needs collaborators."
                )
            }
        } else {
            items(goals, key = { it.id }) { goal ->
                GoalCard(
                    goal = goal,
                    ownGoal = goal.ownerId == currentUserId || (demoMode && goal.ownerId == "demo-user"),
                    demoMode = demoMode,
                    onConnect = { onConnect(goal) }
                )
            }
        }
    }
}

@Composable
private fun GoalCard(goal: Goal, ownGoal: Boolean, demoMode: Boolean, onConnect: () -> Unit) {
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
            Text(goal.description.ifBlank { "A new opportunity to bring good people together." }, color = Color(0xFFB7B7CC), fontSize = 13.sp, lineHeight = 20.sp)
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
        }
    }
}

@Composable
private fun MyGoalsPanel(goals: List<Goal>, demoMode: Boolean, onAdd: () -> Unit) {
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
                    GoalCard(goal, ownGoal = true, demoMode = demoMode, onConnect = {})
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
                Text("Enter the member's profile UUID. In the full product, these controls should also be available directly from each profile.", color = Color(0xFFB7B7CC), fontSize = 12.sp, lineHeight = 18.sp)
                OutlinedTextField(target, { target = it.take(36) }, label = { Text("Member profile UUID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
private fun GoalDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, List<String>) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Product building") }
    var skills by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Publish a goal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it.take(100) }, label = { Text("Goal title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(2000) }, label = { Text("What do you want to achieve?") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(category, { category = it.take(50) }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(skills, { skills = it.take(300) }, label = { Text("Skills needed, comma-separated") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                onCreate(title.trim(), description.trim(), category.trim(), skills.split(",").map { it.trim() }.filter(String::isNotBlank).take(20))
            }, enabled = title.trim().length >= 3) { Text("Publish") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RoomDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("Collaboration") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create an opportunity room") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it.take(100) }, label = { Text("Room title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(1500) }, label = { Text("What will people work on?") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(topic, { topic = it.take(50) }, label = { Text("Topic") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(title.trim(), description.trim(), topic.trim()) }, enabled = title.trim().length >= 3) { Text("Create room") }
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

private fun demoGoals(): List<Goal> = listOf(
    Goal("demo-g1", "sample-person-1", "Build a useful learning app", "Looking for someone who enjoys UX, Android development, and testing small ideas with real learners.", "Product building", listOf("Android", "UX research", "Education"), "active"),
    Goal("demo-g2", "sample-person-2", "Make local businesses easier to find", "Explore a community-first directory with trustworthy listings and simple tools for small businesses.", "Community", listOf("Design", "Marketing", "Research"), "active"),
    Goal("demo-g3", "sample-person-3", "Launch a beginner coding circle", "Create a friendly weekly room where beginners build one small project at a time.", "Learning", listOf("Mentoring", "Kotlin", "Planning"), "active")
)

private fun demoRooms(): List<OpportunityRoom> = listOf(
    OpportunityRoom("demo-room-1", "sample-person-1", "Build in public", "Share progress, ask practical questions, and help each other remove blockers.", "Product building"),
    OpportunityRoom("demo-room-2", "sample-person-2", "Learn by shipping", "A low-pressure space for small projects, feedback, and consistent practice.", "Learning")
)

package com.example.test

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.test.ui.theme.TestTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationChannel(this)
        
        val api = TodoApi.create()
        val tokenManager = TokenManager(this)

        setContent {
            TestTheme {
                val navController = rememberNavController()
                var startDestination by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val token = tokenManager.token.first()
                    startDestination = if (token != null) "home" else "login"
                }

                if (startDestination != null) {
                    NavHost(navController = navController, startDestination = startDestination!!) {
                        composable("login") {
                            LoginScreen(
                                api = api,
                                tokenManager = tokenManager,
                                onLoginSuccess = { 
                                    navController.navigate("home") { 
                                        popUpTo("login") { inclusive = true } 
                                    } 
                                },
                                onNavigateToRegister = { navController.navigate("register") }
                            )
                        }
                        composable("register") {
                            RegisterScreen(
                                api = api,
                                onRegisterSuccess = { 
                                    navController.navigate("login") 
                                },
                                onNavigateToLogin = { navController.navigate("login") }
                            )
                        }
                        composable("home") {
                            TodoApp(api, tokenManager, onLogout = {
                                navController.navigate("login") { 
                                    popUpTo("home") { inclusive = true } 
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = "Recordatorios de Tareas"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel("todo_channel_high", name, importance).apply {
            enableVibration(true)
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

@Composable
fun TodoApp(api: TodoApi, tokenManager: TokenManager, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf(listOf<TodoTask>()) }
    var showDialog by remember { mutableStateOf(false) }
    var taskToEdit by remember { mutableStateOf<TodoTask?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<Category?>(null) }
    var userToken by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        tokenManager.token.first()?.let {
            userToken = "Bearer $it"
            loadTasks(api, userToken) { tasks = it }
        }
    }

    // Permission launcher for Android 13+
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val filteredTasks = tasks.filter {
        (selectedCategory == null || it.category == selectedCategory) &&
                (searchQuery.isEmpty() || it.title.contains(searchQuery, ignoreCase = true))
    }

    TodoAppContent(
        tasks = filteredTasks,
        showDialog = showDialog,
        taskToEdit = taskToEdit,
        searchQuery = searchQuery,
        selectedCategory = selectedCategory,
        onSearchQueryChange = { searchQuery = it },
        onCategorySelect = { selectedCategory = if (selectedCategory == it) null else it },
        onShowDialogChange = { 
            showDialog = it
            if (!it) taskToEdit = null
        },
        onLogout = {
            scope.launch {
                tokenManager.clearToken()
                onLogout()
            }
        },
        onAddTask = { newTask ->
            scope.launch {
                val isoDate = newTask.alarmTime?.let { 
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).format(Date(it))
                }
                val request = CreateTaskRequest(
                    title = newTask.title,
                    category = newTask.category.name,
                    priority = newTask.priority.name,
                    status = "PENDING",
                    reminderDateTime = isoDate,
                    source = "APP"
                )
                try {
                    val response = api.createTask(userToken, request)
                    if (response.isSuccessful && response.body()?.success == true) {
                        loadTasks(api, userToken) { tasks = it }
                        response.body()?.data?.let { 
                            val taskWithId = newTask.copy(id = it.id)
                            scheduleNotification(context, taskWithId) 
                        }
                        showDialog = false
                    } else {
                        Toast.makeText(context, response.body()?.message ?: "Error al crear tarea", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onEditTask = { updatedTask ->
            scope.launch {
                val isoDate = updatedTask.alarmTime?.let { 
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).format(Date(it))
                }
                val request = UpdateTaskRequest(
                    title = updatedTask.title,
                    category = updatedTask.category.name,
                    priority = updatedTask.priority.name,
                    status = if (updatedTask.isCompleted) "COMPLETED" else "PENDING",
                    reminderDateTime = isoDate
                )
                try {
                    val response = api.updateTask(userToken, updatedTask.id, request)
                    if (response.isSuccessful && response.body()?.success == true) {
                        loadTasks(api, userToken) { tasks = it }
                        cancelNotification(context, updatedTask.id)
                        scheduleNotification(context, updatedTask)
                        taskToEdit = null
                        showDialog = false
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onToggleCompletion = { task ->
            scope.launch {
                try {
                    val response = api.toggleComplete(userToken, task.id)
                    if (response.isSuccessful && response.body()?.success == true) {
                        loadTasks(api, userToken) { tasks = it }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onDelete = { task ->
            scope.launch {
                try {
                    val response = api.deleteTask(userToken, task.id)
                    if (response.isSuccessful && response.body()?.success == true) {
                        cancelNotification(context, task.id)
                        loadTasks(api, userToken) { tasks = it }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onStartEdit = { task ->
            taskToEdit = task
            showDialog = true
        }
    )
}

private suspend fun loadTasks(api: TodoApi, token: String, onResult: (List<TodoTask>) -> Unit) {
    try {
        val response = api.getTasks(token)
        if (response.isSuccessful && response.body()?.success == true) {
            val list = response.body()?.data?.tasks?.map { dto ->
                val alarmMillis = dto.reminderDateTime?.let {
                    try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                        sdf.timeZone = TimeZone.getTimeZone("UTC")
                        sdf.parse(it)?.time
                    } catch (e: Exception) { null }
                }
                TodoTask(
                    id = dto.id,
                    title = dto.title,
                    category = try { Category.valueOf(dto.category) } catch (e: Exception) { Category.Personal },
                    priority = try { Priority.valueOf(dto.priority) } catch (e: Exception) { Priority.Media },
                    isCompleted = dto.status == "COMPLETED",
                    alarmTime = alarmMillis
                )
            } ?: emptyList()
            onResult(list)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun scheduleNotification(context: Context, task: TodoTask) {
    val alarmTime = task.alarmTime ?: return
    if (alarmTime <= System.currentTimeMillis()) return

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, TodoNotificationReceiver::class.java).apply {
        putExtra("TASK_TITLE", task.title)
        putExtra("TASK_ID", task.id.hashCode())
        action = "com.example.test.ACTION_TASK_ALARM_${task.id}"
    }

    val pendingIntent = PendingIntent.getBroadcast(
        context,
        task.id.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, pendingIntent)
        } else {
            val intentSettings = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            context.startActivity(intentSettings)
        }
    } else {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, pendingIntent)
    }
}

fun cancelNotification(context: Context, taskId: String) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, TodoNotificationReceiver::class.java).apply {
        action = "com.example.test.ACTION_TASK_ALARM_$taskId"
    }
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        taskId.hashCode(),
        intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )
    if (pendingIntent != null) {
        alarmManager.cancel(pendingIntent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoAppContent(
    tasks: List<TodoTask>,
    showDialog: Boolean,
    taskToEdit: TodoTask?,
    searchQuery: String,
    selectedCategory: Category?,
    onSearchQueryChange: (String) -> Unit,
    onCategorySelect: (Category) -> Unit,
    onShowDialogChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onAddTask: (TodoTask) -> Unit,
    onEditTask: (TodoTask) -> Unit,
    onToggleCompletion: (TodoTask) -> Unit,
    onDelete: (TodoTask) -> Unit,
    onStartEdit: (TodoTask) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Mis Tareas", fontWeight = FontWeight.ExtraBold) },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Cerrar Sesión")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onShowDialogChange(true) }) {
                Icon(Icons.Default.Add, contentDescription = "Agregar Tarea")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 20.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Buscar tareas...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(Category.entries) { category ->
                    val isSelected = selectedCategory == category
                    Surface(
                        onClick = { onCategorySelect(category) },
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(category.name, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    TodoItem(task, { onToggleCompletion(task) }, { onDelete(task) }, { onStartEdit(task) })
                }
            }
        }

        if (showDialog) {
            AddTaskDialog(taskToEdit, { onShowDialogChange(false) }, onAddTask, onEditTask)
        }
    }
}

@Composable
fun TodoItem(task: TodoTask, onToggle: () -> Unit, onDelete: () -> Unit, onEdit: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(24.dp),
        color = if (task.isCompleted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(if (task.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (task.isCompleted) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.Bold, textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            task.category.name,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (task.alarmTime != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.Notifications, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(task.alarmTime)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskDialog(taskToEdit: TodoTask?, onDismiss: () -> Unit, onAddTask: (TodoTask) -> Unit, onEditTask: (TodoTask) -> Unit) {
    var title by remember { mutableStateOf(taskToEdit?.title ?: "") }
    var category by remember { mutableStateOf(taskToEdit?.category ?: Category.Personal) }
    var priority by remember { mutableStateOf(taskToEdit?.priority ?: Priority.Media) }
    var alarmTime by remember { mutableStateOf(taskToEdit?.alarmTime) }
    
    var categoryExpanded by remember { mutableStateOf(false) }
    var priorityExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val calendar = Calendar.getInstance()
    alarmTime?.let { calendar.timeInMillis = it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (taskToEdit == null) "Nueva Tarea" else "Editar Tarea", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Category Dropdown
                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = !categoryExpanded }
                ) {
                    OutlinedTextField(
                        value = category.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Categoría") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        Category.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.name) },
                                onClick = {
                                    category = cat
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }

                // Priority Dropdown
                ExposedDropdownMenuBox(
                    expanded = priorityExpanded,
                    onExpandedChange = { priorityExpanded = !priorityExpanded }
                ) {
                    OutlinedTextField(
                        value = priority.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Prioridad") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = priorityExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = priorityExpanded,
                        onDismissRequest = { priorityExpanded = false }
                    ) {
                        Priority.entries.forEach { prio ->
                            DropdownMenuItem(
                                text = { Text(prio.name) },
                                onClick = {
                                    priority = prio
                                    priorityExpanded = false
                                }
                            )
                        }
                    }
                }

                Surface(
                    onClick = {
                        DatePickerDialog(context, { _, y, m, d ->
                            calendar.set(y, m, d)
                            TimePickerDialog(context, { _, h, min ->
                                calendar.set(Calendar.HOUR_OF_DAY, h)
                                calendar.set(Calendar.MINUTE, min)
                                alarmTime = calendar.timeInMillis
                            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
                        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
                    },
                    color = MaterialTheme.colorScheme.primaryContainer.copy(0.4f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (alarmTime == null) "Programar Alarma" 
                            else SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(alarmTime!!)),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val task = TodoTask(taskToEdit?.id ?: "", title, category, priority, taskToEdit?.isCompleted ?: false, alarmTime)
                        if (taskToEdit == null) onAddTask(task) else onEditTask(task)
                    }
                },
                shape = RoundedCornerShape(12.dp)
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

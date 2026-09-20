package com.ahs.callmap

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ============================================================================
// 1. DOMAIN MODELS & DATA STRUCTURES
// ============================================================================

enum class SourceTrustLevel {
    LOCAL_VERIFIED,   // بيانات محلية من الهاتف (موثوقة شخصياً)
    REMOTE_AVAILABLE, // خدمة تعريف متصل خارجية
    PUBLIC_METADATA,  // بيانات عامة
    UNVERIFIED        // غير مؤكدة
}

data class SourceInfo(
    val sourceName: String,
    val trustLevel: SourceTrustLevel,
    val description: String
)

data class LocationData(
    val address: String?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isLiveGps: Boolean = false, // تمييز الموقع الحقيقي عن العنوان الدفتري
    val source: String
)

data class CallerRecord(
    val rawPhoneNumber: String,
    val normalizedPhoneNumber: String,
    val namesFound: List<Pair<String, SourceInfo>>,
    val locationInfo: LocationData?
)

data class AIAnalysisResult(
    val summary: String,
    val confidenceScore: Float,
    val warnings: List<String>
)

// ============================================================================
// 2. PHONE NUMBER NORMALIZER
// ============================================================================

object PhoneNormalizer {
    /**
     * تحويل الرقم المدخل إلى الصيغة الدولية E.164
     */
    fun normalizeToE164(rawNumber: String, defaultCountryCode: String = "+964"): String? {
        val digitsOnly = rawNumber.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.isBlank()) return null

        return when {
            digitsOnly.startsWith("+") -> digitsOnly
            digitsOnly.startsWith("00") -> "+" + digitsOnly.substring(2)
            digitsOnly.startsWith("0") -> defaultCountryCode + digitsOnly.substring(1)
            else -> defaultCountryCode + digitsOnly
        }
    }
}

// ============================================================================
// 3. DATA SOURCES & REPOSITORY (MODULAR ARCHITECTURE)
// ============================================================================

interface CallerDataSource {
    val sourceName: String
    val isEnabled: Boolean
    suspend fun fetchCallerData(phoneNumberE164: String): CallerRecord?
}

// أ. مصدر جهات الاتصال المحلية
class ContactsDataSource(private val context: Context) : CallerDataSource {
    override val sourceName: String = "جهات اتصال الهاتف"
    override val isEnabled: Boolean = true

    override suspend fun fetchCallerData(phoneNumberE164: String): CallerRecord? {
        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
            .appendPath(phoneNumberE164)
            .build()
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val contactName = cursor.getString(nameIndex)
                        return CallerRecord(
                            rawPhoneNumber = phoneNumberE164,
                            normalizedPhoneNumber = phoneNumberE164,
                            namesFound = listOf(
                                Pair(
                                    contactName,
                                    SourceInfo(
                                        sourceName = sourceName,
                                        trustLevel = SourceTrustLevel.LOCAL_VERIFIED,
                                        description = "حالة: موثوق بالنسبة لبياناتك المحلية"
                                    )
                                )
                            ),
                            locationInfo = null
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}

// ب. مصدر خدمة تعريف المتصل العامة/الرسمية
class CallerIdApiDataSource : CallerDataSource {
    override val sourceName: String = "خدمة تعريف المتصل"
    override val isEnabled: Boolean = true

    override suspend fun fetchCallerData(phoneNumberE164: String): CallerRecord {
        return CallerRecord(
            rawPhoneNumber = phoneNumberE164,
            normalizedPhoneNumber = phoneNumberE164,
            namesFound = listOf(
                Pair(
                    "محمد أحمد",
                    SourceInfo(
                        sourceName = sourceName,
                        trustLevel = SourceTrustLevel.REMOTE_AVAILABLE,
                        description = "حالة: متوفر عبر الدليل العام"
                    )
                )
            ),
            locationInfo = LocationData(
                address = "بغداد - الكرادة",
                latitude = 33.3128,
                longitude = 44.3615,
                isLiveGps = false,
                source = "سجل دليل العناوين"
            )
        )
    }
}

// مستودع تجميع وتصفية البيانات
class CallerRepositoryImpl(
    private val dataSources: List<CallerDataSource>
) {
    suspend fun searchNumber(phoneNumberE164: String): List<CallerRecord> {
        val results = mutableListOf<CallerRecord>()
        dataSources.filter { it.isEnabled }.forEach { source ->
            try {
                source.fetchCallerData(phoneNumberE164)?.let { record ->
                    results.add(record)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return results
    }
}

// ============================================================================
// 4. AI ANALYZER ENGINE
// ============================================================================

class CallerIdAiAnalyzer {
    fun analyze(records: List<CallerRecord>): AIAnalysisResult {
        if (records.isEmpty()) {
            return AIAnalysisResult(
                summary = "لم يتم العثور على بيانات مرتبطة بهذا الرقم في المصادر المتاحة.",
                confidenceScore = 0.0f,
                warnings = emptyList()
            )
        }

        val allNames = records.flatMap { it.namesFound.map { pair -> pair.first } }
        val uniqueNamesCount = allNames.distinct().size
        val hasLocation = records.any { it.locationInfo != null }

        val warnings = mutableListOf<String>()
        val locationMatch = records.mapNotNull { it.locationInfo }.firstOrNull()
        
        if (locationMatch != null && !locationMatch.isLiveGps) {
            warnings.add("تنبيه: العنوان المرتبط بالبيانات مسجل ضمن الدليل ولا يمثل الموقع الحالي الحي للهاتف.")
        }

        val summaryText = buildString {
            append("تم العثور على الرقم في ${records.size} مصدر/مصادر. ")
            if (uniqueNamesCount == 1) {
                append("الاسم متطابق تماماً عبر المصادر. ")
            } else if (uniqueNamesCount > 1) {
                append("يوجد اختلاف بسيط في صيغ الاسم عبر المصادر. ")
            }
            if (!hasLocation) {
                append("لا توجد إحداثيات أو عناوين جغرافية موثوقة.")
            }
        }

        return AIAnalysisResult(
            summary = summaryText,
            confidenceScore = if (uniqueNamesCount == 1) 0.95f else 0.60f,
            warnings = warnings
        )
    }
}

// ============================================================================
// 5. VIEWMODEL & STATE MANAGEMENT
// ============================================================================

sealed interface SearchUiState {
    object Idle : SearchUiState
    object Loading : SearchUiState
    data class Success(
        val normalizedNumber: String,
        val records: List<CallerRecord>,
        val aiAnalysis: AIAnalysisResult
    ) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

class SearchViewModel(
    private val repository: CallerRepositoryImpl,
    private val aiAnalyzer: CallerIdAiAnalyzer = CallerIdAiAnalyzer()
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    fun performSearch(inputNumber: String) {
        if (inputNumber.isBlank()) return

        viewModelScope.launch {
            _uiState.value = SearchUiState.Loading

            val e164Number = PhoneNormalizer.normalizeToE164(inputNumber)
            if (e164Number == null) {
                _uiState.value = SearchUiState.Error("صيغة الرقم غير صحيحة.")
                return@launch
            }

            val records = repository.searchNumber(e164Number)
            val aiResult = aiAnalyzer.analyze(records)

            _uiState.value = SearchUiState.Success(
                normalizedNumber = e164Number,
                records = records,
                aiAnalysis = aiResult
            )
        }
    }
}

// ============================================================================
// 6. UI COMPONENTS & SCREENS (JETPACK COMPOSE)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onNavigateToSearch: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "AHS CallMap", style = MaterialTheme.typography.titleLarge)
                        Text(text = "Intelligent Caller Information & Map", style = MaterialTheme.typography.labelMedium)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ElevatedButton(
                onClick = onNavigateToSearch,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("بحث عن رقم")
            }

            OutlinedButton(
                onClick = { },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("الخريطة")
            }

            OutlinedButton(
                onClick = { },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("مصادر البيانات")
            }

            OutlinedButton(
                onClick = { },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("الإعدادات والخصوصية")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "آخر الاتصالات", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun CallerResultCard(
    name: String,
    sourceName: String,
    trustStatusText: String,
    locationText: String?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "الاسم: $name", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "المصدر: $sourceName", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = trustStatusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "الموقع: ${locationText ?: "غير متوفر"}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun AiAnalysisSummaryCard(summary: String, warnings: List<String>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "تحليل الذكاء الاصطناعي",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            warnings.forEach { warning ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Red
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel) {
    var searchInput by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("البحث اليدوي عن رقم") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = searchInput,
                onValueChange = { searchInput = it },
                label = { Text("أدخل الرقم (مثال: 0786 444 1431)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.performSearch(searchInput) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("بحث ومطابقة البيانات")
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (val state = uiState) {
                is SearchUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is SearchUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                is SearchUiState.Success -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Text(
                                text = "الصيغة الدولية: ${state.normalizedNumber}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        item {
                            AiAnalysisSummaryCard(
                                summary = state.aiAnalysis.summary,
                                warnings = state.aiAnalysis.warnings
                            )
                        }

                        items(state.records) { record ->
                            record.namesFound.forEach { (name, sourceInfo) ->
                                CallerResultCard(
                                    name = name,
                                    sourceName = sourceInfo.sourceName,
                                    trustStatusText = sourceInfo.description,
                                    locationText = record.locationInfo?.address
                                )
                            }

                            record.locationInfo?.let { location ->
                                if (location.latitude != null && location.longitude != null) {
                                    Button(
                                        onClick = {
                                            val gmmIntentUri = Uri.parse("geo:${location.latitude},${location.longitude}?q=${Uri.encode(location.address ?: "")}")
                                            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                                            mapIntent.setPackage("com.google.android.apps.maps")
                                            context.startActivity(mapIntent)
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                    ) {
                                        Text("عرض العنوان المسجل على Google Maps")
                                    }
                                }
                            }
                        }
                    }
                }
                SearchUiState.Idle -> {
                    Text("أدخل رقماً واضغط على بحث لعرض النتائج وتقييم المصادر.")
                }
            }
        }
    }
}

// ============================================================================
// 7. MAIN ACTIVITY ENTRY POINT
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // تهيئة موديولات المصادر وإدارة البيانات
        val contactsDS = ContactsDataSource(applicationContext)
        val callerIdDS = CallerIdApiDataSource()
        val repository = CallerRepositoryImpl(listOf(contactsDS, callerIdDS))
        val searchViewModel = SearchViewModel(repository)

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "dashboard") {
                        composable("dashboard") {
                            DashboardScreen(
                                onNavigateToSearch = { navController.navigate("search") }
                            )
                        }
                        composable("search") {
                            SearchScreen(viewModel = searchViewModel)
                        }
                    }
                }
            }
        }
    }
}

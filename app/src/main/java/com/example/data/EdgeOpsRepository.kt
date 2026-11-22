package com.example.data

import android.content.Context
import android.util.Log
import com.example.R
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.persistentCacheSettings
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private const val REPO_TAG = "EdgeOpsRepository"

class EdgeOpsRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    /**
     * Device-local RAM vault for raw unredacted Slack text.
     * Raw PII stays strictly on-device for the Before/After Privacy Diff and is NEVER written to Cloud Firestore.
     */
    private val localRawMessageVault = ConcurrentHashMap<String, String>()

    fun rememberLocalRawMessage(incidentId: String, rawText: String) {
        val clean = rawText.trim().take(2000)
        if (incidentId.isNotBlank() && clean.isNotBlank()) {
            localRawMessageVault[incidentId] = clean
        }
    }

    constructor(context: Context) : this(
        createConfiguredFirestore(context)
    )

    companion object {
        fun createConfiguredFirestore(context: Context): FirebaseFirestore {
            val databaseId = context.applicationContext.getString(R.string.firestore_database_id)
            val firestore = FirebaseFirestore.getInstance(databaseId)
            try {
                // Explicitly enable persistent offline cache so EdgeOps works offline seamlessly
                firestore.firestoreSettings = firestoreSettings {
                    setLocalCacheSettings(persistentCacheSettings {})
                }
            } catch (e: IllegalStateException) {
                Log.d(REPO_TAG, "Firestore persistent cache already configured: ${e.message}")
            }
            return firestore
        }

        fun buildDeterministicSlackDocId(userId: String, externalId: String): String {
            val cleanExt = externalId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val uidPrefix = userId.replace(Regex("[^a-zA-Z0-9]"), "").take(10)
            val candidate = "slack_${uidPrefix}_${cleanExt}"
            return if (candidate.length <= 120) {
                candidate
            } else {
                "slack_${uidPrefix}_${abs(externalId.hashCode())}"
            }
        }
    }

    private fun requireUserId(): String {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        }
        return uid
    }

    // --- Incidents Collection (/incidents/{incidentId}) ---

    fun observeIncidents(userId: String): Flow<List<Incident>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Authenticated user mismatch." }
        val path = "incidents"
        emitAll(
            db.collection(path)
                .whereEqualTo("userId", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        Incident::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                        .filter { it.industryPack == "LIVE_SLACK" }
                        .map { incident ->
                            val localRaw = localRawMessageVault[incident.id]
                            when {
                                !localRaw.isNullOrBlank() -> incident.copy(rawMessage = localRaw)
                                incident.rawMessage.isBlank() -> incident.copy(rawMessage = incident.scrubbedMessage)
                                else -> incident
                            }
                        }
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    /**
     * Inserts a synced Slack message into Firestore if not already present (idempotent by externalId).
     * Never overwrites existing resolved/investigated user work.
     */
    suspend fun insertSyncedIncidentIfAbsent(
        externalId: String,
        title: String,
        channel: String,
        severity: IncidentSeverity,
        status: IncidentStatus,
        noiseSuppressed: Boolean,
        rawMessage: String,
        scrubbedMessage: String,
        redactionCount: Int,
        triageSummary: String,
        suggestedReply: String,
        inferenceSource: InferenceSourceType,
        existingDiagnosis: String? = null
    ): Result<Boolean> {
        var docPath = "incidents"
        return try {
            val uid = requireUserId()
            val docId = buildDeterministicSlackDocId(uid, externalId)
            val docRef = db.collection("incidents").document(docId)
            docPath = docRef.path

            // Keep unredacted text strictly on-device — NEVER persist rawMessage to Cloud Firestore
            rememberLocalRawMessage(docId, rawMessage)

            val payload = mapOf<String, Any?>(
                "userId" to uid,
                "title" to title.trim().take(200).ifBlank { "Slack Alert" },
                "channel" to channel.trim().take(60).ifBlank { "#general" },
                "industryPack" to "LIVE_SLACK",
                "severity" to severity.name,
                "status" to status.name,
                "noiseSuppressed" to noiseSuppressed,
                "scrubbedMessage" to scrubbedMessage.trim().take(2000).ifBlank { title.take(2000) },
                "redactionCount" to redactionCount.coerceIn(0, 50),
                "triageSummary" to triageSummary.trim().take(1000),
                "suggestedReply" to suggestedReply.trim().take(2000),
                "inferenceSource" to inferenceSource.name,
                "diagnosis" to existingDiagnosis?.trim()?.take(3000)?.ifBlank { null },
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }

            docRef.set(payload).await()
            Result.success(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveLocalTriage(
        incidentId: String,
        scrubbedMessage: String,
        redactionCount: Int,
        triageSummary: String,
        suggestedReply: String,
        inferenceSource: InferenceSourceType,
        noiseSuppressed: Boolean,
        severity: IncidentSeverity,
        status: IncidentStatus
    ): Result<Unit> {
        val docPath = "incidents/$incidentId"
        return try {
            requireUserId()
            require(incidentId.isNotBlank()) { "Incident ID required" }
            val updateMap = mapOf(
                "scrubbedMessage" to scrubbedMessage.trim().take(2000),
                "redactionCount" to redactionCount.coerceIn(0, 50),
                "triageSummary" to triageSummary.trim().take(1000),
                "suggestedReply" to suggestedReply.trim().take(2000),
                "inferenceSource" to inferenceSource.name,
                "noiseSuppressed" to noiseSuppressed,
                "severity" to severity.name,
                "status" to status.name,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("incidents").document(incidentId).update(updateMap).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveCloudInvestigation(
        incidentId: String,
        diagnosis: String,
        toolsUsed: List<String>,
        totalToolsAvailable: Int,
        suggestedReply: String
    ): Result<Unit> {
        val docPath = "incidents/$incidentId"
        return try {
            requireUserId()
            require(incidentId.isNotBlank()) { "Incident ID required" }
            val updateMap = mapOf(
                "diagnosis" to diagnosis.trim().take(3000),
                "toolsUsed" to toolsUsed.take(10).map { it.take(120) },
                "totalToolsAvailable" to totalToolsAvailable.coerceIn(0, 500),
                "investigatedRole" to "SUPPORT",
                "suggestedReply" to suggestedReply.trim().take(2000),
                "inferenceSource" to InferenceSourceType.CLOUD_INVESTIGATION.name,
                "status" to IncidentStatus.INVESTIGATED.name,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("incidents").document(incidentId).update(updateMap).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Marks an incident as resolved in Firestore only (strictly read-only to Slack; never posts to Slack).
     */
    suspend fun markIncidentResolved(
        incidentId: String,
        replyNote: String
    ): Result<Unit> {
        val docPath = "incidents/$incidentId"
        return try {
            requireUserId()
            require(incidentId.isNotBlank()) { "Incident ID required" }
            val updateMap = mapOf(
                "status" to IncidentStatus.RESOLVED.name,
                "approvedReply" to replyNote.trim().take(2000),
                "approvedByRole" to "SUPPORT",
                "slackPosted" to false,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("incidents").document(incidentId).update(updateMap).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleNoiseStatus(incidentId: String, markAsNoise: Boolean): Result<Unit> {
        val docPath = "incidents/$incidentId"
        return try {
            requireUserId()
            require(incidentId.isNotBlank()) { "Incident ID required" }
            val newStatus = if (markAsNoise) IncidentStatus.HIDDEN_NOISE else IncidentStatus.NEEDS_ACTION
            db.collection("incidents").document(incidentId).update(
                mapOf(
                    "status" to newStatus.name,
                    "noiseSuppressed" to markAsNoise,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    // --- Connectors Collection (/connectors/{connectorId}) ---

    fun observeConnectors(userId: String): Flow<List<Connector>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Authenticated user mismatch." }
        val path = "connectors"
        emitAll(
            db.collection(path)
                .whereEqualTo("userId", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        Connector::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun addConnector(
        name: String,
        connectorType: String,
        endpointUrl: String,
        toolNames: List<String>,
        statusLabel: String
    ): Result<String> {
        var docPath = "connectors"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("connectors").document()
            docPath = docRef.path
            val safeTools = toolNames.take(50).map { it.trim().take(120) }.filter { it.isNotBlank() }
            val payload = mapOf(
                "userId" to uid,
                "name" to name.trim().take(120),
                "connectorType" to connectorType,
                "endpointUrl" to endpointUrl.trim().take(500),
                "toolNames" to safeTools,
                "toolCount" to safeTools.size.coerceIn(0, 200),
                "enabled" to true,
                "statusLabel" to statusLabel.trim().take(80),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleConnector(connectorId: String, enabled: Boolean): Result<Unit> {
        val docPath = "connectors/$connectorId"
        return try {
            requireUserId()
            require(connectorId.isNotBlank()) { "Connector ID required" }
            db.collection("connectors").document(connectorId).update(
                mapOf(
                    "enabled" to enabled,
                    "statusLabel" to if (enabled) "Connected" else "Paused",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteConnector(connectorId: String): Result<Unit> {
        val docPath = "connectors/$connectorId"
        return try {
            requireUserId()
            require(connectorId.isNotBlank()) { "Connector ID required" }
            db.collection("connectors").document(connectorId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    // --- User Settings Document (/user_settings/{userId}) ---

    fun observeUserSettings(userId: String): Flow<UserSettings> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Authenticated user mismatch." }
        val path = "user_settings/$uid"
        emitAll(
            db.collection("user_settings").document(uid)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            UserSettings::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        ) ?: UserSettings(id = uid, userId = uid)
                    } else {
                        UserSettings(id = uid, userId = uid)
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    // --- Organizations Collection (/orgs/{orgId}) ---

    fun observeOrganization(userId: String, orgId: String): Flow<Organization?> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Authenticated user mismatch." }
        if (orgId.isBlank()) {
            emit(null)
            return@flow
        }
        val path = "orgs/$orgId"
        emitAll(
            db.collection("orgs").document(orgId)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            Organization::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else {
                        null
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.GET, path)
                    }
                    emit(null)
                }
        )
    }

    suspend fun createOrganization(orgName: String): Result<Organization> {
        var docPath = "orgs"
        return try {
            val uid = requireUserId()
            val cleanName = orgName.trim().take(120)
            require(cleanName.isNotBlank()) { "Workspace name is required." }

            val docRef = db.collection("orgs").document()
            docPath = docRef.path
            val webhookUrl = "${CloudRunInvestigatorClient.DEFAULT_BASE_URL}/webhooks/slack/${docRef.id}"
            val payload = mapOf(
                "userId" to uid,
                "orgName" to cleanName,
                "operatorRole" to "SUPPORT",
                "webhookUrl" to webhookUrl,
                "slackConnected" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(
                Organization(
                    id = docRef.id,
                    userId = uid,
                    orgName = cleanName,
                    operatorRole = "SUPPORT",
                    webhookUrl = webhookUrl,
                    slackConnected = false
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun completeOnboarding(
        orgId: String?,
        orgName: String?
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docPath = "user_settings/$uid"
        return try {
            val docRef = db.collection("user_settings").document(uid)
            val existing = docRef.get().await()
            val cleanOrgId = orgId?.trim()?.take(128)?.ifBlank { null }
            val cleanOrgName = orgName?.trim()?.take(120)?.ifBlank { null }

            if (existing.exists()) {
                val updateMap = mapOf<String, Any?>(
                    "orgId" to cleanOrgId,
                    "orgName" to cleanOrgName,
                    "activeRole" to "SUPPORT",
                    "onboardingCompleted" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                docRef.update(updateMap).await()
            } else {
                val createMap = mapOf<String, Any?>(
                    "userId" to uid,
                    "orgId" to cleanOrgId,
                    "orgName" to cleanOrgName,
                    "onboardingCompleted" to true,
                    "activePack" to "LIVE_SLACK",
                    "activeRole" to "SUPPORT",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                docRef.set(createMap).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }
}

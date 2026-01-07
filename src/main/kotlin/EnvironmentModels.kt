package com.agh

import kotlinx.serialization.Serializable
import java.time.LocalDateTime

@Serializable
enum class DeviceState {
    ON,
    OFF,
    BROKEN
}

@Serializable
enum class BlindState {
    OPEN,
    CLOSED
}

@Serializable
data class LightDevice(
    val id: String,
    val roomId: String,
    val state: DeviceState,
    val brightness: Int = 100 // 0-100
)

@Serializable
data class PrinterDevice(
    val id: String,
    val roomId: String,
    val state: DeviceState,
    val tonerLevel: Int = 100, // 0-100
    val paperLevel: Int = 100 // 0-100
)

@Serializable
data class MotionSensor(
    val id: String,
    val roomId: String,
    val motionDetected: Boolean,
    val lastMotionTime: String? = null
)

@Serializable
data class TemperatureSensor(
    val id: String,
    val roomId: String,
    val temperature: Double // w stopniach Celsjusza
)

@Serializable
data class BlindsDevice(
    val id: String,
    val roomId: String,
    val state: BlindState
)

@Serializable
data class Meeting(
    val startTime: String, // LocalDateTime jako string
    val endTime: String, // LocalDateTime jako string
    val title: String = "Spotkanie"
)

@Serializable
data class Room(
    val id: String,
    val name: String,
    val lights: List<LightDevice>,
    val printer: PrinterDevice?,
    val motionSensor: MotionSensor,
    val temperatureSensor: TemperatureSensor,
    val blinds: BlindsDevice?,
    val peopleCount: Int = 0,
    val scheduledMeetings: List<Meeting> = emptyList()
)

@Serializable
data class EnvironmentState(
    val simulationTime: String, // LocalDateTime jako string
    val rooms: List<Room>,
    val externalTemperature: Double,
    val timeSpeedMultiplier: Double = 1.0,
    val powerOutage: Boolean = false,
    val daylightIntensity: Double = 1.0 // 0.0-1.0
)

@Serializable
data class EnvironmentEvent(
    val type: String, // "motion", "temperature_change", "device_failure", "power_outage", etc.
    val roomId: String?,
    val deviceId: String?,
    val timestamp: String,
    val description: String
)

@Serializable
data class Alert(
    val id: String,
    val type: String, // "low_toner", "low_paper", "printer_failure", etc.
    val printerId: String,
    val roomId: String?,
    val roomName: String?,
    val message: String,
    val timestamp: String,
    val severity: String = "warning" // "info", "warning", "error"
)

@Serializable
data class PrinterControlRequest(
    val action: String,
    val level: Int? = null
)

@Serializable
data class HeatingControlRequest(
    val isHeating: Boolean
)

@Serializable
data class RoomHeatingResponse(
    val roomId: String,
    val isHeating: Boolean
)

// Odpowiedzi API
@Serializable
data class ApiResponse(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null,
    val alertId: String? = null
)

@Serializable
data class ErrorResponse(
    val error: String
)

@Serializable
data class TemperatureResponse(
    val externalTemperature: Double,
    val rooms: List<RoomTemperature>
)

@Serializable
data class RoomTemperature(
    val roomId: String,
    val roomName: String,
    val temperature: Double
)

@Serializable
data class RoomMotion(
    val roomId: String,
    val roomName: String,
    val motionDetected: Boolean,
    val peopleCount: Int,
    val lastMotionTime: String?
)

@Serializable
data class DeviceInfo(
    val id: String,
    val type: String,
    val roomId: String,
    val roomName: String,
    val state: String,
    val brightness: Int? = null,
    val tonerLevel: Int? = null,
    val paperLevel: Int? = null
)

// Komunikacja NL między agentami
@Serializable
enum class MessageType {
    REQUEST,   // Prośba o akcję
    INFORM,    // Informacja
    QUERY,     // Zapytanie
    RESPONSE   // Odpowiedź
}

@Serializable
data class AgentMessage(
    val id: String,
    val from: String,  // ID agenta wysyłającego
    val to: String,    // ID agenta docelowego lub "broadcast"
    val type: MessageType,
    val content: String,   // Treść wiadomości w języku naturalnym
    val timestamp: String, // ISO_LOCAL_DATE_TIME
    val context: Map<String, String>? = null // Dodatkowy kontekst
)

@Serializable
data class AgentMessageRequest(
    val from: String,
    val to: String,
    val type: MessageType,
    val content: String,
    val context: Map<String, String>? = null
)

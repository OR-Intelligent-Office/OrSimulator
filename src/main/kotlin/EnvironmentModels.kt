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


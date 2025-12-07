package com.agh

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class EnvironmentSimulator(
    private val rooms: List<RoomConfig> = defaultRooms(),
    private val timeSpeedMultiplier: Double = 1.0,
    private val failureProbability: Double = 0.01,
) {
    private val random = Random.Default
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private var currentState: EnvironmentState = createInitialState()
    private var simulationStartTime: LocalDateTime = LocalDateTime.now()

    // Parametry symulacji
    private var externalTemperature: Double = 15.0 // temperatura zewnętrzna w stopniach C
    private var heatingOn: Boolean = true
    private var powerOutage: Boolean = false
    private var daylightIntensity: Double = 1.0

    // Generator zdarzeń
    private val events = mutableListOf<EnvironmentEvent>()

    fun getCurrentState(): EnvironmentState = currentState

    fun getEvents(): List<EnvironmentEvent> = events.toList()

    fun clearEvents() {
        events.clear()
    }

    fun update(deltaMinutes: Double = 1.0) {
        val actualDelta = deltaMinutes * timeSpeedMultiplier
        val currentTime = simulationStartTime.plusMinutes(actualDelta.toLong())

        // Aktualizacja czasu symulacji
        currentState =
            currentState.copy(
                simulationTime = currentTime.format(formatter),
                externalTemperature = externalTemperature,
                timeSpeedMultiplier = timeSpeedMultiplier,
                powerOutage = powerOutage,
                daylightIntensity = daylightIntensity,
            )

        updatePeopleMovement(currentTime)
        updateTemperatures()
        checkDeviceFailures(currentTime)
        generateRandomEvents(currentTime)
        updateDeviceStates(currentTime)
    }

    private fun updatePeopleMovement(currentTime: LocalDateTime) {
        val hour = currentTime.hour
        val isWorkingHours = hour in 8..16

        currentState.rooms.forEach { room ->
            val baseProbability =
                if (isWorkingHours) {
                    when (hour) {
                        in 8..10 -> 0.3 // rano więcej ruchu
                        in 10..12 -> 0.4 // szczyt
                        in 12..14 -> 0.2 // przerwa obiadowa
                        in 14..16 -> 0.3 // popołudnie
                        else -> 0.1
                    }
                } else {
                    0.05 // poza godzinami pracy
                }

            val motionDetected = random.nextDouble() < baseProbability
            val peopleCount =
                if (motionDetected) {
                    random.nextInt(4) + 1 // 1-4 osoby
                } else {
                    0
                }

            val updatedSensor =
                room.motionSensor.copy(
                    motionDetected = motionDetected,
                    lastMotionTime = if (motionDetected) currentTime.format(formatter) else room.motionSensor.lastMotionTime,
                )

            val updatedRoom =
                room.copy(
                    motionSensor = updatedSensor,
                    peopleCount = peopleCount,
                )

            val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
            currentState = currentState.copy(rooms = updatedRooms)

            if (motionDetected) {
                events.add(
                    EnvironmentEvent(
                        type = "motion",
                        roomId = room.id,
                        deviceId = updatedSensor.id,
                        timestamp = currentTime.format(formatter),
                        description = "Wykryto ruch w sali ${room.name}",
                    ),
                )
            }
        }
    }

    private fun updateTemperatures() {
        val heatLossFactor = 0.1 // współczynnik strat ciepła
        val targetTemperature = if (heatingOn) 22.0 else externalTemperature
        val noise = random.nextDouble() * 1.0 - 0.5 // szum losowy -0.5 do 0.5

        currentState.rooms.forEach { room ->
            val currentTemp = room.temperatureSensor.temperature
            val tempDiff = targetTemperature - currentTemp
            val newTemp = currentTemp + tempDiff * 0.1 + noise // powolna zmiana + szum

            // Ograniczenie zakresu temperatury
            val clampedTemp = max(15.0, min(28.0, newTemp))

            val updatedSensor = room.temperatureSensor.copy(temperature = clampedTemp)
            val updatedRoom = room.copy(temperatureSensor = updatedSensor)

            val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
            currentState = currentState.copy(rooms = updatedRooms)
        }
    }

    private fun checkDeviceFailures(currentTime: LocalDateTime) {
        currentState.rooms.forEach { room ->
            // Sprawdzenie awarii światła
            room.lights.forEach { light ->
                if (light.state != DeviceState.BROKEN && random.nextDouble() < failureProbability / 60.0) {
                    val updatedLight = light.copy(state = DeviceState.BROKEN)
                    val updatedLights = room.lights.map { if (it.id == light.id) updatedLight else it }
                    val updatedRoom = room.copy(lights = updatedLights)
                    val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
                    currentState = currentState.copy(rooms = updatedRooms)

                    events.add(
                        EnvironmentEvent(
                            type = "device_failure",
                            roomId = room.id,
                            deviceId = light.id,
                            timestamp = currentTime.format(formatter),
                            description = "Awaria światła ${light.id} w sali ${room.name}",
                        ),
                    )
                }
            }

            // Sprawdzenie awarii drukarki
            room.printer?.let { printer ->
                if (printer.state != DeviceState.BROKEN && random.nextDouble() < failureProbability / 60.0) {
                    val updatedPrinter = printer.copy(state = DeviceState.BROKEN)
                    val updatedRoom = room.copy(printer = updatedPrinter)
                    val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
                    currentState = currentState.copy(rooms = updatedRooms)

                    events.add(
                        EnvironmentEvent(
                            type = "device_failure",
                            roomId = room.id,
                            deviceId = printer.id,
                            timestamp = currentTime.format(formatter),
                            description = "Awaria drukarki ${printer.id} w sali ${room.name}",
                        ),
                    )
                }
            }

            // Sprawdzenie awarii czujnika ruchu
            if (random.nextDouble() < (failureProbability / 2) / 60.0) { // 0.5% szansy
                val updatedSensor = room.motionSensor.copy(motionDetected = false)
                val updatedRoom = room.copy(motionSensor = updatedSensor)
                val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
                currentState = currentState.copy(rooms = updatedRooms)

                events.add(
                    EnvironmentEvent(
                        type = "device_failure",
                        roomId = room.id,
                        deviceId = room.motionSensor.id,
                        timestamp = currentTime.format(formatter),
                        description = "Awaria czujnika ruchu w sali ${room.name}",
                    ),
                )
            }
        }
    }

    private fun generateRandomEvents(currentTime: LocalDateTime) {
        // Nagły skok temperatury na zewnątrz (np. otwarcie okna)
        if (random.nextDouble() < 0.01) { // 1% szansy
            externalTemperature += random.nextDouble() * 15.0 - 5.0 // -5.0 do 10.0
            externalTemperature = max(-10.0, min(35.0, externalTemperature))

            events.add(
                EnvironmentEvent(
                    type = "temperature_spike",
                    roomId = null,
                    deviceId = null,
                    timestamp = currentTime.format(formatter),
                    description = "Nagła zmiana temperatury zewnętrznej do ${String.format("%.1f", externalTemperature)}°C",
                ),
            )
        }

        // Nagły spadek natężenia światła dziennego (zachmurzenie)
        if (random.nextDouble() < 0.02) { // 2% szansy
            daylightIntensity = random.nextDouble() * 0.7 + 0.3 // 0.3 do 1.0

            events.add(
                EnvironmentEvent(
                    type = "daylight_change",
                    roomId = null,
                    deviceId = null,
                    timestamp = currentTime.format(formatter),
                    description = "Zmiana natężenia światła dziennego do ${String.format("%.1f", daylightIntensity * 100)}%",
                ),
            )
        }

        // Tymczasowa utrata zasilania
        if (random.nextDouble() < 0.005) { // 0.5% szansy
            powerOutage = true
            events.add(
                EnvironmentEvent(
                    type = "power_outage",
                    roomId = null,
                    deviceId = null,
                    timestamp = currentTime.format(formatter),
                    description = "Utrata zasilania",
                ),
            )
        } else if (powerOutage && random.nextDouble() < 0.1) { // 10% szansy na przywrócenie
            powerOutage = false
            events.add(
                EnvironmentEvent(
                    type = "power_restored",
                    roomId = null,
                    deviceId = null,
                    timestamp = currentTime.format(formatter),
                    description = "Przywrócenie zasilania",
                ),
            )
        }
    }

    private fun updateDeviceStates(currentTime: LocalDateTime) {
        // Prosta logika: jeśli wykryto ruch i światło jest wyłączone, włącz je
        // Jeśli brak ruchu przez dłuższy czas, wyłącz światło
        currentState.rooms.forEach { room ->
            val updatedLights =
                room.lights.map { light ->
                    if (light.state == DeviceState.BROKEN || powerOutage) {
                        light.copy(state = DeviceState.OFF)
                    } else if (room.motionSensor.motionDetected && light.state == DeviceState.OFF) {
                        light.copy(state = DeviceState.ON)
                    } else if (!room.motionSensor.motionDetected && light.state == DeviceState.ON) {
                        // Wyłącz po 5 minutach bez ruchu
                        light.copy(state = DeviceState.OFF)
                    } else {
                        light
                    }
                }

            val updatedRoom = room.copy(lights = updatedLights)
            val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
            currentState = currentState.copy(rooms = updatedRooms)
        }
    }

    private fun createInitialState(): EnvironmentState {
        val initialRooms =
            rooms.map { config ->
                Room(
                    id = config.id,
                    name = config.name,
                    lights =
                        config.lightIds.map { lightId ->
                            LightDevice(
                                id = lightId,
                                roomId = config.id,
                                state = DeviceState.OFF,
                                brightness = 100,
                            )
                        },
                    printer =
                        config.printerId?.let { printerId ->
                            PrinterDevice(
                                id = printerId,
                                roomId = config.id,
                                state = DeviceState.OFF,
                                tonerLevel = random.nextInt(51) + 50, // 50-100
                                paperLevel = random.nextInt(51) + 50, // 50-100
                            )
                        },
                    motionSensor =
                        MotionSensor(
                            id = "sensor_${config.id}",
                            roomId = config.id,
                            motionDetected = false,
                        ),
                    temperatureSensor =
                        TemperatureSensor(
                            id = "temp_${config.id}",
                            roomId = config.id,
                            temperature = 20.0 + random.nextDouble() * 4.0 - 2.0, // 18.0 do 22.0
                        ),
                    blinds =
                        BlindsDevice(
                            id = "blinds_${config.id}",
                            roomId = config.id,
                            state = BlindState.CLOSED,
                        ),
                    peopleCount = 0,
                )
            }

        return EnvironmentState(
            simulationTime = LocalDateTime.now().format(formatter),
            rooms = initialRooms,
            externalTemperature = externalTemperature,
            timeSpeedMultiplier = timeSpeedMultiplier,
            powerOutage = false,
            daylightIntensity = 1.0,
        )
    }

    fun setTimeSpeedMultiplier(multiplier: Double) {
        // Można dodać setter dla prędkości czasu
    }

    fun setExternalTemperature(temp: Double) {
        externalTemperature = temp
    }

    fun setHeating(on: Boolean) {
        heatingOn = on
    }
}

// Konfiguracja pokoi
data class RoomConfig(
    val id: String,
    val name: String,
    val lightIds: List<String>,
    val printerId: String? = null,
    val hasBlinds: Boolean = false,
)

fun defaultRooms(): List<RoomConfig> =
    listOf(
        RoomConfig(
            id = "room_208",
            name = "Sala 208",
            lightIds = listOf("light_208_1", "light_208_2"),
            printerId = "printer_208",
            hasBlinds = true,
        ),
        RoomConfig(
            id = "room_209",
            name = "Sala 209",
            lightIds = listOf("light_209_1"),
            printerId = "printer_209",
            hasBlinds = true,
        ),
        RoomConfig(
            id = "room_210",
            name = "Sala 210",
            lightIds = listOf("light_210_1", "light_210_2", "light_210_3"),
            hasBlinds = false,
        ),
        RoomConfig(
            id = "office_101",
            name = "Biuro 101",
            lightIds = listOf("light_101_1"),
            printerId = "printer_101",
            hasBlinds = true,
        ),
    )

package com.agh

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class EnvironmentSimulator(
    private val rooms: List<RoomConfig>,
    private val timeSpeedMultiplier: Double,
    private val failureProbability: Double,
) {
    private val random = Random.Default
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private var currentState: EnvironmentState = createInitialState()
    private var currentSimulationTime: LocalDateTime = LocalDateTime.now()

    // Parametry symulacji
    private var externalTemperature: Double = 15.0 // temperatura zewnętrzna w stopniach C
    private var heatingOn: Boolean = true
    private var powerOutage: Boolean = false
    private var daylightIntensity: Double = 1.0

    // Generator zdarzeń
    private val events = mutableListOf<EnvironmentEvent>()
    
    // Alerty od agentów
    private val alerts = mutableListOf<Alert>()
    
    // Tracking czasu dla automatycznego uzupełniania zasobów (po godzinie symulacji)
    private val tonerDepletedAt = mutableMapOf<String, LocalDateTime>() // printerId -> czas symulacji wyczerpania
    private val paperDepletedAt = mutableMapOf<String, LocalDateTime>() // printerId -> czas symulacji wyczerpania

    /**
     * Zwraca aktualny stan środowiska (wszystkie pokoje, urządzenia, parametry)
     */
    fun getCurrentState(): EnvironmentState = currentState

    /**
     * Zwraca listę wszystkich zdarzeń środowiskowych (ruchy, awarie, zmiany temperatury, itp.)
     */
    fun getEvents(): List<EnvironmentEvent> = events.toList()

    /**
     * Czyści listę zdarzeń środowiskowych
     */
    fun clearEvents() {
        events.clear()
    }
    
    /**
     * Dodaje alert od agenta do systemu
     * Przechowuje maksymalnie 100 ostatnich alertów
     */
    fun addAlert(alert: Alert) {
        alerts.add(alert)
        if (alerts.size > 100) {
            alerts.removeAt(0)
        }
    }
    
    /**
     * Zwraca listę wszystkich alertów od agentów
     */
    fun getAlerts(): List<Alert> = alerts.toList()
    
    /**
     * Czyści listę alertów
     */
    fun clearAlerts() {
        alerts.clear()
    }

    /**
     * Główna funkcja aktualizująca symulację środowiska
     * Wywoływana co sekundę rzeczywistą (co minutę symulacji)
     * Aktualizuje wszystkie aspekty środowiska: ruch ludzi, temperatury, awarie, zdarzenia, stany urządzeń i zasoby drukarek
     */
    fun update(deltaMinutes: Double = 1.0) {
        val actualDelta = deltaMinutes * timeSpeedMultiplier
        currentSimulationTime = currentSimulationTime.plusMinutes(actualDelta.toLong())

        currentState =
            currentState.copy(
                simulationTime = currentSimulationTime.format(formatter),
                externalTemperature = externalTemperature,
                timeSpeedMultiplier = timeSpeedMultiplier,
                powerOutage = powerOutage,
                daylightIntensity = daylightIntensity,
            )

        updatePeopleMovement(currentSimulationTime)
        updateTemperatures()
        checkDeviceFailures(currentSimulationTime)
        generateRandomEvents(currentSimulationTime)
        updateDeviceStates(currentSimulationTime)
        replenishPrinterResources()
    }

    /**
     * Symuluje ruch ludzi w pokojach na podstawie godzin pracy
     * Prawdopodobieństwo wykrycia ruchu zależy od pory dnia (wyższe w godzinach pracy 8-16)
     * Liczba osób w pokoju: 1-4 gdy wykryto ruch, 0 gdy brak ruchu
     */
    private fun updatePeopleMovement(currentTime: LocalDateTime) {
        val hour = currentTime.hour
        val isWorkingHours = hour in 8..16

        currentState.rooms.forEach { room ->
            val baseProbability =
                if (isWorkingHours) {
                    when (hour) {
                        in 8..10 -> 0.3
                        in 10..12 -> 0.4
                        in 12..14 -> 0.2
                        in 14..16 -> 0.3
                        else -> 0.1
                    }
                } else {
                    0.05
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

    /**
     * Aktualizuje temperatury w pokojach
     * Temperatura dąży do wartości docelowej (22°C jeśli ogrzewanie włączone, temperatura zewnętrzna jeśli wyłączone)
     * Zawiera losowy szum i jest ograniczona do zakresu 15-28°C
     */
    private fun updateTemperatures() {
        val targetTemperature = if (heatingOn) 22.0 else externalTemperature

        currentState.rooms.forEach { room ->
            val currentTemp = room.temperatureSensor.temperature
            val tempDiff = targetTemperature - currentTemp
            val noise = random.nextDouble() * 1.0 - 0.5
            val newTemp = currentTemp + tempDiff * 0.1 + noise

            val clampedTemp = max(15.0, min(28.0, newTemp))

            val updatedSensor = room.temperatureSensor.copy(temperature = clampedTemp)
            val updatedRoom = room.copy(temperatureSensor = updatedSensor)

            val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
            currentState = currentState.copy(rooms = updatedRooms)
        }
    }

    /**
     * Sprawdza i generuje losowe awarie urządzeń
     * Może powodować awarie: świateł, drukarek, czujników ruchu
     * Prawdopodobieństwo awarii zależy od parametru failureProbability
     */
    private fun checkDeviceFailures(currentTime: LocalDateTime) {
        currentState.rooms.forEach { room ->
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

            if (random.nextDouble() < (failureProbability / 2) / 60.0) {
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

    /**
     * Generuje losowe zdarzenia środowiskowe:
     * - Nagłe zmiany temperatury zewnętrznej
     * - Zmiany natężenia światła dziennego (zachmurzenie)
     * - Tymczasowa utrata zasilania i jego przywrócenie
     */
    private fun generateRandomEvents(currentTime: LocalDateTime) {
        if (random.nextDouble() < 0.01) {
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

        if (random.nextDouble() < 0.02) {
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

        if (random.nextDouble() < 0.005) {
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
        } else if (powerOutage && random.nextDouble() < 0.1) {
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

    /**
     * Aktualizuje stany urządzeń w pokojach na podstawie wykrytego ruchu
     * - Światła: włączają się automatycznie przy wykryciu ruchu, wyłączają po braku ruchu
     * - Drukarki: włączają się z 50% prawdopodobieństwem przy wykryciu ruchu (tylko jeśli zasoby > 0), wyłączają po braku ruchu
     * - Zużywa zasoby drukarki gdy jest włączona (toner i papier)
     * - Jeśli jeden zasób = 0%, drugi przestaje się zużywać
     */
    private fun updateDeviceStates(currentTime: LocalDateTime) {
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

            // Automatyczne włączanie/wyłączanie drukarki na podstawie ruchu
            // Drukarka włącza się z 50% prawdopodobieństwem przy wykryciu ruchu
            val updatedPrinter = room.printer?.let { printer ->
                if (printer.state == DeviceState.BROKEN || powerOutage) {
                    printer.copy(state = DeviceState.OFF)
                } else if (room.motionSensor.motionDetected && printer.state == DeviceState.OFF) {
                    // Włącz tylko jeśli zasoby są dostępne i z 50% prawdopodobieństwem
                    if (printer.tonerLevel > 0 && printer.paperLevel > 0) {
                        if (random.nextDouble() < 0.5) {
                            printer.copy(state = DeviceState.ON)
                        } else {
                            printer.copy(state = DeviceState.OFF)
                        }
                    } else {
                        printer.copy(state = DeviceState.OFF)
                    }
                } else if (!room.motionSensor.motionDetected && printer.state == DeviceState.ON) {
                    printer.copy(state = DeviceState.OFF)
                } else {
                    printer
                }
            }

            // Zużywanie zasobów drukarki gdy jest włączona
            val finalPrinter = updatedPrinter?.let { printer ->
                if (printer.state == DeviceState.ON && !powerOutage) {
                    // Jeśli jeden zasób = 0%, drugi przestaje się zużywać
                    val canConsumeToner = printer.tonerLevel > 0 && printer.paperLevel > 0
                    val canConsumePaper = printer.tonerLevel > 0 && printer.paperLevel > 0
                    
                    // Intensywność zużycia zależy od obecności ludzi w pokoju
                    val baseConsumption = if (room.peopleCount > 0) 1.0 else 0.3
                    
                    // Zużycie: 0.5-1.5% tonera i 1.0-2.5% papieru na minutę symulacji
                    val tonerConsumption = if (canConsumeToner) {
                        (random.nextDouble() * 1.0 + 0.5) * baseConsumption
                    } else {
                        0.0
                    }
                    val paperConsumption = if (canConsumePaper) {
                        (random.nextDouble() * 1.5 + 1.0) * baseConsumption
                    } else {
                        0.0
                    }
                    
                    val newTonerLevel = max(0, (printer.tonerLevel - tonerConsumption).toInt())
                    val newPaperLevel = max(0, (printer.paperLevel - paperConsumption).toInt())
                    
                    // Zapisz czas wyczerpania zasobów dla automatycznego uzupełniania
                    if (newTonerLevel == 0 && printer.tonerLevel > 0) {
                        tonerDepletedAt[printer.id] = currentTime
                    }
                    if (newPaperLevel == 0 && printer.paperLevel > 0) {
                        paperDepletedAt[printer.id] = currentTime
                    }
                    
                    // Wyłącz drukarkę jeśli zasoby się wyczerpały
                    val newState = if (newTonerLevel == 0 || newPaperLevel == 0) {
                        DeviceState.OFF
                    } else {
                        printer.state
                    }
                    
                    printer.copy(
                        state = newState,
                        tonerLevel = newTonerLevel,
                        paperLevel = newPaperLevel
                    )
                } else {
                    printer
                }
            }
            
            val updatedRoom = room.copy(
                lights = updatedLights,
                printer = finalPrinter
            )
            val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
            currentState = currentState.copy(rooms = updatedRooms)
        }
    }
    
    /**
     * Automatyczne uzupełnianie zasobów drukarki:
     * Oba warunki muszą być spełnione jednocześnie:
     * 1. Musi minąć godzina symulacji (60 sekund rzeczywistych)
     * 2. Ktoś musi być w pokoju (peopleCount > 0)
     * W symulacji 1 minuta = 1 sekunda rzeczywista, więc godzina symulacji = 60 sekund rzeczywistych
     * Sprawdzane niezależnie od stanu drukarki
     */
    private fun replenishPrinterResources() {
        currentState.rooms.forEach { room ->
            room.printer?.let { printer ->
                var updatedPrinter = printer
                var needsUpdate = false
                
                // Sprawdź toner - uzupełnij tylko jeśli minęła godzina I ktoś jest w pokoju
                tonerDepletedAt[printer.id]?.let { depletedTime ->
                    val duration = java.time.Duration.between(depletedTime, currentSimulationTime)
                    val hoursSinceDepletion = duration.toHours()
                    val shouldReplenish = (hoursSinceDepletion >= 1L && room.peopleCount > 0)
                    
                    if (shouldReplenish && printer.tonerLevel == 0) {
                        updatedPrinter = updatedPrinter.copy(tonerLevel = 100)
                        tonerDepletedAt.remove(printer.id)
                        needsUpdate = true
                    }
                }
                
                // Sprawdź papier - uzupełnij tylko jeśli minęła godzina I ktoś jest w pokoju
                paperDepletedAt[printer.id]?.let { depletedTime ->
                    val duration = java.time.Duration.between(depletedTime, currentSimulationTime)
                    val hoursSinceDepletion = duration.toHours()
                    val shouldReplenish = (hoursSinceDepletion >= 1L && room.peopleCount > 0)
                    
                    if (shouldReplenish && updatedPrinter.paperLevel == 0) {
                        updatedPrinter = updatedPrinter.copy(paperLevel = 100)
                        paperDepletedAt.remove(printer.id)
                        needsUpdate = true
                    }
                }
                
                // Zaktualizuj stan tylko jeśli coś się zmieniło
                if (needsUpdate) {
                    val updatedRoom = room.copy(printer = updatedPrinter)
                    val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
                    currentState = currentState.copy(rooms = updatedRooms)
                }
            }
        }
    }

    /**
     * Tworzy początkowy stan środowiska na podstawie konfiguracji pokoi
     * Inicjalizuje wszystkie urządzenia w stanie OFF, losowe poziomy zasobów drukarek (50-100%)
     */
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

    /**
     * Ustawia mnożnik prędkości symulacji (niezaimplementowane)
     */
    fun setTimeSpeedMultiplier(multiplier: Double) {
    }

    /**
     * Ustawia temperaturę zewnętrzną
     */
    fun setExternalTemperature(temp: Double) {
        externalTemperature = temp
    }

    /**
     * Włącza/wyłącza ogrzewanie
     */
    fun setHeating(on: Boolean) {
        heatingOn = on
    }

    /**
     * Ustawia stan drukarki (ON/OFF/BROKEN)
     * Nie pozwala włączyć drukarki jeśli toner lub papier = 0%
     * @return true jeśli operacja się powiodła, false jeśli drukarka nie została znaleziona lub brak zasobów
     */
    fun setPrinterState(printerId: String, state: DeviceState): Boolean {
        val room = currentState.rooms.find { it.printer?.id == printerId }
        val printer = room?.printer ?: return false

        if (state == DeviceState.ON && (printer.tonerLevel == 0 || printer.paperLevel == 0)) {
            return false
        }

        val updatedPrinter = printer.copy(state = state)
        val updatedRoom = room.copy(printer = updatedPrinter)
        val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
        currentState = currentState.copy(rooms = updatedRooms)
        return true
    }

    /**
     * Ustawia poziom tonera drukarki (0-100%)
     * Automatycznie zapisuje czas wyczerpania jeśli poziom spadł do 0%
     * @return true jeśli operacja się powiodła, false jeśli drukarka nie została znaleziona
     */
    fun setPrinterTonerLevel(printerId: String, level: Int): Boolean {
        val room = currentState.rooms.find { it.printer?.id == printerId }
        val printer = room?.printer ?: return false

        val clampedLevel = max(0, min(100, level))
        val updatedPrinter = printer.copy(tonerLevel = clampedLevel)
        
        if (clampedLevel == 0 && printer.tonerLevel > 0) {
            tonerDepletedAt[printerId] = currentSimulationTime
        } else if (clampedLevel > 0) {
            tonerDepletedAt.remove(printerId)
        }
        
        val updatedRoom = room.copy(printer = updatedPrinter)
        val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
        currentState = currentState.copy(rooms = updatedRooms)
        return true
    }

    /**
     * Ustawia poziom papieru drukarki (0-100%)
     * Automatycznie zapisuje czas wyczerpania jeśli poziom spadł do 0%
     * @return true jeśli operacja się powiodła, false jeśli drukarka nie została znaleziona
     */
    fun setPrinterPaperLevel(printerId: String, level: Int): Boolean {
        val room = currentState.rooms.find { it.printer?.id == printerId }
        val printer = room?.printer ?: return false

        val clampedLevel = max(0, min(100, level))
        val updatedPrinter = printer.copy(paperLevel = clampedLevel)
        
        if (clampedLevel == 0 && printer.paperLevel > 0) {
            paperDepletedAt[printerId] = currentSimulationTime
        } else if (clampedLevel > 0) {
            paperDepletedAt.remove(printerId)
        }
        
        val updatedRoom = room.copy(printer = updatedPrinter)
        val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
        currentState = currentState.copy(rooms = updatedRooms)
        return true
    }

    /**
     * Zwraca stan drukarki o podanym ID
     * @return PrinterDevice jeśli znaleziono, null w przeciwnym razie
     */
    fun getPrinter(printerId: String): PrinterDevice? {
        return currentState.rooms.find { it.printer?.id == printerId }?.printer
    }
}

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


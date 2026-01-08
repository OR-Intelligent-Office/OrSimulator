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

    // Ogrzewanie per pokój: roomId -> czy ogrzewanie włączone
    private val roomHeating = mutableMapOf<String, Boolean>()
    private var powerOutage: Boolean = false
    private var daylightIntensity: Double = 1.0

    // Generator zdarzeń
    private val events = mutableListOf<EnvironmentEvent>()

    // Alerty od agentów
    private val alerts = mutableListOf<Alert>()

    // Komunikaty NL między agentami
    private val agentMessages = mutableListOf<AgentMessage>()
    private var messageIdCounter = 0L

    // Tracking czasu dla automatycznego uzupełniania zasobów (po godzinie symulacji)
    private val tonerDepletedAt = mutableMapOf<String, LocalDateTime>() // printerId -> czas symulacji wyczerpania
    private val paperDepletedAt = mutableMapOf<String, LocalDateTime>() // printerId -> czas symulacji wyczerpania

    // Tracking sprawdzonych przedziałów czasowych dla generowania spotkań
    // roomId -> Set<String> gdzie String to "YYYY-MM-DDTHH:MM" (początek przedziału 30-minutowego)
    private val checkedTimeSlots = mutableMapOf<String, MutableSet<String>>()

    // Tracking pobytu osób w pokojach
    // roomId -> czas do którego osoby zostaną w pokoju (null = brak osób)
    private val peopleStayUntil = mutableMapOf<String, LocalDateTime?>()

    // roomId -> aktualna liczba osób
    private val currentPeopleCount = mutableMapOf<String, Int>()

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
     * Dodaje wiadomość NL od agenta
     * Przechowuje maksymalnie 200 ostatnich wiadomości
     */
    fun addAgentMessage(message: AgentMessage) {
        agentMessages.add(message)
        if (agentMessages.size > 200) {
            agentMessages.removeAt(0)
        }
    }

    /**
     * Zwraca wszystkie wiadomości dla konkretnego agenta (gdzie to=agentId lub from=agentId)
     */
    fun getMessagesForAgent(agentId: String): List<AgentMessage> =
        agentMessages.filter {
            it.to == agentId || it.from == agentId || it.to == "broadcast"
        }

    /**
     * Zwraca wszystkie wiadomości
     */
    fun getAllMessages(): List<AgentMessage> = agentMessages.toList()

    /**
     * Zwraca nowe wiadomości dla agenta (po określonym czasie)
     */
    fun getNewMessagesForAgent(
        agentId: String,
        afterTimestamp: String?,
    ): List<AgentMessage> {
        if (afterTimestamp == null) {
            return getMessagesForAgent(agentId)
        }
        return agentMessages.filter {
            (it.to == agentId || it.to == "broadcast") && it.timestamp > afterTimestamp
        }
    }

    /**
     * Czyści listę wiadomości
     */
    fun clearMessages() {
        agentMessages.clear()
    }

    /**
     * Generuje unikalne ID dla wiadomości
     */
    fun generateMessageId(): String {
        messageIdCounter++
        return "msg_${System.currentTimeMillis()}_$messageIdCounter"
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
        
        // Automatycznie wyłącz wszystkie ogrzewania przy awarii zasilania
        if (powerOutage) {
            roomHeating.keys.forEach { roomId ->
                roomHeating[roomId] = false
            }
        }
        
        updateTemperatures()
        checkDeviceFailures(currentSimulationTime)
        generateRandomEvents(currentSimulationTime)
        updateDeviceStates(currentSimulationTime)
        replenishPrinterResources()
        updateScheduledMeetings(currentSimulationTime)
    }

    /**
     * Symuluje ruch ludzi w pokojach na podstawie godzin pracy
     * Osoby zostają w pokoju przez 10-45 minut symulacji (realistyczny czas pobytu)
     * Prawdopodobieństwo przyjścia nowych osób zależy od pory dnia
     */
    private fun updatePeopleMovement(currentTime: LocalDateTime) {
        val hour = currentTime.hour
        val isWorkingHours = hour in 8..17

        currentState.rooms.forEach { room ->
            val stayUntil = peopleStayUntil[room.id]
            val currentCount = currentPeopleCount[room.id] ?: 0

            // Sprawdź czy osoby powinny opuścić pokój
            val shouldLeave = stayUntil != null && currentTime.isAfter(stayUntil)

            // Prawdopodobieństwo że nowe osoby przyjdą (tylko jeśli pokój pusty lub losowo dojdą)
            val arrivalProbability =
                if (isWorkingHours) {
                    when (hour) {
                        in 8..9 -> 0.15

                        // Poranne przychodzenie
                        in 9..12 -> 0.08

                        // Przedpołudnie - rzadziej
                        in 12..13 -> 0.05

                        // Przerwa obiadowa
                        in 13..16 -> 0.08

                        // Popołudnie
                        in 16..17 -> 0.03

                        // Koniec pracy
                        else -> 0.02
                    }
                } else {
                    0.01 // Poza godzinami pracy - bardzo rzadko
                }

            var newPeopleCount = currentCount
            var motionDetected = currentCount > 0

            if (shouldLeave) {
                // Część osób wychodzi (losowo 50-100% osób)
                val leavingRatio = 0.5 + random.nextDouble() * 0.5
                val leaving = (currentCount * leavingRatio).toInt()
                newPeopleCount = max(0, currentCount - max(1, leaving))

                if (newPeopleCount > 0) {
                    // Część osób zostaje - przedłuż czas pobytu o 5-15 minut
                    val extraStay = 5 + random.nextInt(11) // 5-15 minut
                    peopleStayUntil[room.id] = currentTime.plusMinutes(extraStay.toLong())
                } else {
                    peopleStayUntil[room.id] = null
                }
            }

            // Nowe osoby mogą przyjść
            if (random.nextDouble() < arrivalProbability) {
                val newArrivals = 1 + random.nextInt(3) // 1-3 nowe osoby
                newPeopleCount += newArrivals
                newPeopleCount = min(8, newPeopleCount) // Max 8 osób w pokoju

                // Ustal czas pobytu: 10-45 minut symulacji
                val stayDuration = 10 + random.nextInt(36) // 10-45 minut
                val newStayUntil = currentTime.plusMinutes(stayDuration.toLong())

                // Weź późniejszy czas jeśli ktoś już był
                val existingStay = peopleStayUntil[room.id]
                if (existingStay == null || newStayUntil.isAfter(existingStay)) {
                    peopleStayUntil[room.id] = newStayUntil
                }

                motionDetected = true

                events.add(
                    EnvironmentEvent(
                        type = "motion",
                        roomId = room.id,
                        deviceId = room.motionSensor.id,
                        timestamp = currentTime.format(formatter),
                        description = "Wykryto ruch w sali ${room.name} ($newArrivals os. przyszło)",
                    ),
                )
            }

            // Zapisz aktualny stan
            currentPeopleCount[room.id] = newPeopleCount

            // Generuj zdarzenia ruchu gdy są ludzie (symulacja czujnika)
            if (newPeopleCount > 0 && random.nextDouble() < 0.3) {
                motionDetected = true
            }

            val updatedSensor =
                room.motionSensor.copy(
                    motionDetected = motionDetected,
                    lastMotionTime = if (motionDetected) currentTime.format(formatter) else room.motionSensor.lastMotionTime,
                )

            val updatedRoom =
                room.copy(
                    motionSensor = updatedSensor,
                    peopleCount = newPeopleCount,
                )

            val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
            currentState = currentState.copy(rooms = updatedRooms)
        }
    }

    /**
     * Aktualizuje temperatury w pokojach
     * Jeśli ogrzewanie włączone: temperatura dąży do 22°C (może się ogrzewać lub chłodzić)
     * Jeśli ogrzewanie wyłączone: temperatura może tylko się chłodzić w kierunku temperatury zewnętrznej (nie może się ogrzewać)
     * Zawiera losowy szum i jest ograniczona do zakresu 15-28°C
     */
    private fun updateTemperatures() {
        currentState.rooms.forEach { room ->
            val currentTemp = room.temperatureSensor.temperature
            val newTemp: Double

            val isRoomHeatingOn = roomHeating[room.id] ?: false
            if (isRoomHeatingOn) {
                // Ogrzewanie włączone dla tego pokoju: dąż do 22°C (może się ogrzewać lub chłodzić)
                val targetTemperature = 22.0
                val tempDiff = targetTemperature - currentTemp
                val change = tempDiff * 0.05 // Zmniejszone z 0.1 na 0.05 - wolniejsze ogrzewanie/chłodzenie
                // Noise w tym samym kierunku co zmiana (dodatni gdy ogrzewanie, ujemny gdy chłodzenie)
                val directionalNoise = if (change >= 0) {
                    random.nextDouble() * 0.1 // Zmniejszone z 0.2 na 0.1
                } else {
                    -random.nextDouble() * 0.1 // Zmniejszone z 0.2 na 0.1
                }
                newTemp = currentTemp + change + directionalNoise
            } else {
                // Ogrzewanie wyłączone: tylko chłodzenie w kierunku temperatury zewnętrznej
                // Jeśli pokój jest cieplejszy niż zewnętrzna, chłodź się
                // Jeśli pokój jest chłodniejszy niż zewnętrzna, temperatura zbliża się do zewnętrznej (ale wolniej)
                if (currentTemp > externalTemperature) {
                    val tempDiff = externalTemperature - currentTemp
                    val change = tempDiff * 0.05 // Zmniejszone z 0.1 na 0.05 - wolniejsze chłodzenie
                    // Noise ujemny (chłodzenie)
                    val directionalNoise = -random.nextDouble() * 0.1 // Zmniejszone z 0.2 na 0.1
                    newTemp = currentTemp + change + directionalNoise
                } else {
                    // Pokój jest chłodniejszy niż zewnętrzna - temperatura wolno zbliża się do zewnętrznej
                    val tempDiff = externalTemperature - currentTemp
                    val change = tempDiff * 0.025 // Zmniejszone z 0.05 na 0.025 - wolniejsze zbliżanie się
                    // Mały noise w kierunku wzrostu (ale bardzo mały, bo ogrzewanie wyłączone)
                    val directionalNoise = random.nextDouble() * 0.05 // Zmniejszone z 0.1 na 0.05
                    newTemp = currentTemp + change + directionalNoise
                }
            }

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
     * - Światła: NIE są sterowane automatycznie - zarządza nimi LightAgent
     *   (tylko wyłączane przy awarii zasilania lub gdy zepsute)
     * - Drukarki: włączają się z 50% prawdopodobieństwem przy wykryciu ruchu (tylko jeśli zasoby > 0), wyłączają po braku ruchu
     * - Zużywa zasoby drukarki gdy jest włączona (toner i papier)
     * - Jeśli jeden zasób = 0%, drugi przestaje się zużywać
     */
    private fun updateDeviceStates(currentTime: LocalDateTime) {
        currentState.rooms.forEach { room ->
            // Światła NIE są automatycznie sterowane - tylko reagują na awarie
            // LightAgent ma pełną kontrolę nad światłami
            val updatedLights =
                room.lights.map { light ->
                    if (light.state == DeviceState.BROKEN) {
                        light.copy(state = DeviceState.OFF)
                    } else if (powerOutage && light.state == DeviceState.ON) {
                        // Tylko wyłącz przy awarii zasilania
                        light.copy(state = DeviceState.OFF)
                    } else {
                        light
                    }
                }

            // Printers are controlled by agents - simulator only turns off on failures
            val updatedPrinter =
                room.printer?.let { printer ->
                    if (printer.state == DeviceState.BROKEN || powerOutage) {
                        printer.copy(state = DeviceState.OFF)
                    } else if (printer.state == DeviceState.ON && (printer.tonerLevel == 0 || printer.paperLevel == 0)) {
                        // Automatically turn off if no resources (agent cannot turn on without resources)
                        printer.copy(state = DeviceState.OFF)
                    } else {
                        // Leave printer state unchanged - controlled by agent via API
                        printer
                    }
                }

            // Resource consumption is controlled by agent - simulator does not consume automatically
            val finalPrinter = updatedPrinter

            val updatedRoom =
                room.copy(
                    lights = updatedLights,
                    printer = finalPrinter,
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
     * Aktualizuje zaplanowane spotkania dla wszystkich pokoi
     * Generuje spotkania na maksymalnie 1 dzień roboczy do przodu
     * Prawdopodobieństwo: 50% w godzinach roboczych (8-16), 20% w godzinach wieczornych (16-22), 0% w nocy (22-8)
     * Każdy przedział czasowy (30 minut) jest sprawdzany tylko raz
     */
    private fun updateScheduledMeetings(currentTime: LocalDateTime) {
        val updatedRooms =
            currentState.rooms.map { room ->
                val roomCheckedSlots = checkedTimeSlots.getOrPut(room.id) { mutableSetOf() }
                val allMeetings = mutableListOf<Meeting>()

                // Pobierz istniejące spotkania które jeszcze nie minęły i są w zakresie 1 dnia roboczego
                val existingMeetings =
                    room.scheduledMeetings.filter { meeting ->
                        val endTime = LocalDateTime.parse(meeting.endTime, formatter)
                        val isInFuture = endTime.isAfter(currentTime)
                        val isWithinWorkingDay = isWithinOneWorkingDay(currentTime, endTime)
                        isInFuture && isWithinWorkingDay
                    }
                allMeetings.addAll(existingMeetings)

                // Oblicz zakres czasowy do sprawdzenia (od teraz do 1 dnia roboczego do przodu)
                val endCheckTime = getOneWorkingDayForward(currentTime)

                // Sprawdź każdy przedział 30-minutowy w zakresie
                var checkTime = currentTime
                // Zaokrąglij do najbliższego 30-minutowego przedziału
                val currentMinute = checkTime.minute
                val roundedMinute = (currentMinute / 30) * 30
                checkTime = checkTime.withMinute(roundedMinute).withSecond(0).withNano(0)

                while (checkTime.isBefore(endCheckTime) || checkTime.isEqual(endCheckTime)) {
                    val timeSlotKey = checkTime.format(formatter)

                    // Sprawdź tylko jeśli ten przedział nie był jeszcze sprawdzony
                    if (!roomCheckedSlots.contains(timeSlotKey)) {
                        val hour = checkTime.hour
                        val probability =
                            when {
                                hour in 8..15 -> 0.5

                                // Godziny robocze: 8-16 (8:00-15:59)
                                hour in 16..21 -> 0.2

                                // Godziny wieczorne: 16-22 (16:00-21:59)
                                else -> 0.0 // Noc: 22-7 (22:00-7:59)
                            }

                        // Sprawdź czy nie ma już spotkania w tym przedziale
                        val hasOverlap =
                            allMeetings.any { meeting ->
                                val meetingStart = LocalDateTime.parse(meeting.startTime, formatter)
                                val meetingEnd = LocalDateTime.parse(meeting.endTime, formatter)
                                checkTime.isBefore(meetingEnd) && checkTime.plusMinutes(30).isAfter(meetingStart)
                            }

                        if (!hasOverlap && random.nextDouble() < probability) {
                            val meetingStart = checkTime
                            val meetingEnd = checkTime.plusMinutes(30)
                            allMeetings.add(
                                Meeting(
                                    startTime = meetingStart.format(formatter),
                                    endTime = meetingEnd.format(formatter),
                                    title = "Spotkanie",
                                ),
                            )
                        }

                        // Oznacz przedział jako sprawdzony
                        roomCheckedSlots.add(timeSlotKey)
                    }

                    // Przejdź do następnego przedziału 30-minutowego
                    checkTime = checkTime.plusMinutes(30)
                }

                // Usuń stare wpisy z checkedTimeSlots (starsze niż 2 dni)
                val twoDaysAgo = currentTime.minusDays(2)
                roomCheckedSlots.removeIf { slotTime ->
                    try {
                        val slot = LocalDateTime.parse(slotTime, formatter)
                        slot.isBefore(twoDaysAgo)
                    } catch (e: Exception) {
                        true // Usuń nieprawidłowe wpisy
                    }
                }

                // Sortuj spotkania według czasu rozpoczęcia
                val sortedMeetings = allMeetings.sortedBy { LocalDateTime.parse(it.startTime, formatter) }

                room.copy(scheduledMeetings = sortedMeetings)
            }

        currentState = currentState.copy(rooms = updatedRooms)
    }

    /**
     * Sprawdza czy podany czas jest w zakresie 1 dnia roboczego do przodu od czasu bazowego
     * Dzień roboczy: poniedziałek-piątek
     */
    private fun isWithinOneWorkingDay(
        baseTime: LocalDateTime,
        checkTime: LocalDateTime,
    ): Boolean {
        if (checkTime.isBefore(baseTime) || checkTime.isEqual(baseTime)) {
            return false
        }

        // Znajdź następny dzień roboczy od baseTime
        var nextWorkingDay = baseTime
        var workingDaysAdded = 0

        // Jeśli jesteśmy w trakcie dnia roboczego (pon-pt), dodaj 1 dzień roboczy
        if (nextWorkingDay.dayOfWeek.value in 1..5) {
            workingDaysAdded = 1
            // Przejdź do końca tego dnia
            nextWorkingDay = nextWorkingDay.withHour(23).withMinute(59).withSecond(59)
        }

        // Przejdź do następnego dnia roboczego
        while (workingDaysAdded < 1) {
            nextWorkingDay = nextWorkingDay.plusDays(1)
            if (nextWorkingDay.dayOfWeek.value in 1..5) {
                workingDaysAdded++
            }
        }

        // Sprawdź czy checkTime jest przed końcem następnego dnia roboczego
        val endOfNextWorkingDay = nextWorkingDay.withHour(23).withMinute(59).withSecond(59)
        return checkTime.isBefore(endOfNextWorkingDay) || checkTime.isEqual(endOfNextWorkingDay)
    }

    /**
     * Zwraca czas końca 1 dnia roboczego do przodu od podanego czasu
     * Dzień roboczy: poniedziałek-piątek
     */
    private fun getOneWorkingDayForward(baseTime: LocalDateTime): LocalDateTime {
        var nextWorkingDay = baseTime
        var workingDaysAdded = 0

        // Jeśli jesteśmy w trakcie dnia roboczego (pon-pt), dodaj 1 dzień roboczy
        if (nextWorkingDay.dayOfWeek.value in 1..5) {
            workingDaysAdded = 1
            // Przejdź do końca tego dnia
            nextWorkingDay = nextWorkingDay.withHour(23).withMinute(59).withSecond(59)
        }

        // Przejdź do końca następnego dnia roboczego
        while (workingDaysAdded < 1) {
            nextWorkingDay = nextWorkingDay.plusDays(1)
            if (nextWorkingDay.dayOfWeek.value in 1..5) {
                workingDaysAdded++
            }
        }

        // Zwróć koniec dnia (23:59:59)
        return nextWorkingDay.withHour(23).withMinute(59).withSecond(59)
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
                    scheduledMeetings = emptyList(),
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
     * Włącza/wyłącza ogrzewanie dla konkretnego pokoju
     * Nie pozwala włączyć ogrzewania podczas awarii zasilania
     * @return true jeśli operacja się powiodła, false jeśli pokój nie został znaleziony lub awaria zasilania
     */
    fun setRoomHeating(
        roomId: String,
        on: Boolean,
    ): Boolean {
        val roomExists = currentState.rooms.any { it.id == roomId }
        if (!roomExists) {
            return false
        }
        
        // Nie można włączyć ogrzewania podczas awarii zasilania
        if (on && powerOutage) {
            return false
        }
        
        roomHeating[roomId] = on
        return true
    }

    /**
     * Zwraca stan ogrzewania dla konkretnego pokoju
     */
    fun getRoomHeating(roomId: String): Boolean = roomHeating[roomId] ?: false

    /**
     * Ustawia stan drukarki (ON/OFF/BROKEN)
     * Nie pozwala włączyć drukarki jeśli toner lub papier = 0%
     * @return true jeśli operacja się powiodła, false jeśli drukarka nie została znaleziona lub brak zasobów
     */
    fun setPrinterState(
        printerId: String,
        state: DeviceState,
    ): Boolean {
        val room = currentState.rooms.find { it.printer?.id == printerId }
        val printer = room?.printer ?: return false

        // Cannot turn on broken printer
        if (state == DeviceState.ON && printer.state == DeviceState.BROKEN) {
            return false
        }

        // Cannot turn on printer during power outage
        if (state == DeviceState.ON && powerOutage) {
            return false
        }

        // Cannot turn on printer without resources
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
    fun setPrinterTonerLevel(
        printerId: String,
        level: Int,
    ): Boolean {
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
    fun setPrinterPaperLevel(
        printerId: String,
        level: Int,
    ): Boolean {
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
    fun getPrinter(printerId: String): PrinterDevice? = currentState.rooms.find { it.printer?.id == printerId }?.printer

    /**
     * Zwraca światło o podanym ID
     * @return LightDevice jeśli znaleziono, null w przeciwnym razie
     */
    fun getLight(lightId: String): LightDevice? {
        currentState.rooms.forEach { room ->
            room.lights.find { it.id == lightId }?.let { return it }
        }
        return null
    }

    /**
     * Ustawia stan światła (ON/OFF)
     * @return true jeśli operacja się powiodła, false jeśli światło nie zostało znalezione lub jest zepsute
     */
    fun setLightState(
        lightId: String,
        state: DeviceState,
        brightness: Int? = null,
    ): Boolean {
        currentState.rooms.forEach { room ->
            val lightIndex = room.lights.indexOfFirst { it.id == lightId }
            if (lightIndex >= 0) {
                val light = room.lights[lightIndex]

                // Nie można włączyć zepsutego światła
                if (light.state == DeviceState.BROKEN && state == DeviceState.ON) {
                    return false
                }

                // Nie można włączyć gdy awaria zasilania
                if (powerOutage && state == DeviceState.ON) {
                    return false
                }

                val newBrightness = brightness ?: light.brightness
                val clampedBrightness = max(0, min(100, newBrightness))

                val updatedLight =
                    light.copy(
                        state = state,
                        brightness = clampedBrightness,
                    )
                val updatedLights = room.lights.toMutableList()
                updatedLights[lightIndex] = updatedLight
                val updatedRoom = room.copy(lights = updatedLights)
                val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
                currentState = currentState.copy(rooms = updatedRooms)
                return true
            }
        }
        return false
    }

    /**
     * Ustawia jasność światła (0-100)
     * @return true jeśli operacja się powiodła, false jeśli światło nie zostało znalezione
     */
    fun setLightBrightness(
        lightId: String,
        brightness: Int,
    ): Boolean {
        currentState.rooms.forEach { room ->
            val lightIndex = room.lights.indexOfFirst { it.id == lightId }
            if (lightIndex >= 0) {
                val light = room.lights[lightIndex]
                val clampedBrightness = max(0, min(100, brightness))

                val updatedLight = light.copy(brightness = clampedBrightness)
                val updatedLights = room.lights.toMutableList()
                updatedLights[lightIndex] = updatedLight
                val updatedRoom = room.copy(lights = updatedLights)
                val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
                currentState = currentState.copy(rooms = updatedRooms)
                return true
            }
        }
        return false
    }

    /**
     * Ustawia stan rolet (OPEN/CLOSED)
     * @return true jeśli operacja się powiodła, false jeśli rolety nie zostały znalezione
     */
    fun setBlindsState(
        blindsId: String,
        state: BlindState,
    ): Boolean {
        currentState.rooms.forEach { room ->
            val blinds = room.blinds
            if (blinds?.id == blindsId) {
                val updatedBlinds = blinds.copy(state = state)
                val updatedRoom = room.copy(blinds = updatedBlinds)
                val updatedRooms = currentState.rooms.map { if (it.id == room.id) updatedRoom else it }
                currentState = currentState.copy(rooms = updatedRooms)
                return true
            }
        }
        return false
    }

    /**
     * Zwraca rolety o podanym ID
     * @return BlindsDevice jeśli znaleziono, null w przeciwnym razie
     */
    fun getBlinds(blindsId: String): BlindsDevice? = currentState.rooms.find { it.blinds?.id == blindsId }?.blinds
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

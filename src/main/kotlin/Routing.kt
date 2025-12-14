package com.agh

import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds

/**
 * Singleton zarządzający instancją symulatora środowiska
 * Inicjalizuje symulator i uruchamia cykliczne aktualizacje w tle
 */
object SimulatorManager {
    private var simulator: EnvironmentSimulator? = null
    private var updateJob: Job? = null

    /**
     * Inicjalizuje symulator i uruchamia aktualizacje w tle
     * Aktualizacja odbywa się co sekundę rzeczywistą (co minutę symulacji)
     */
    fun initialize(
        application: Application,
        startBackgroundUpdates: Boolean = true,
    ) {
        if (simulator == null) {
            simulator =
                EnvironmentSimulator(
                    rooms = defaultRooms(),
                    timeSpeedMultiplier = 1.0,
                    failureProbability = 0.01,
                )

            if (startBackgroundUpdates) {
                updateJob =
                    CoroutineScope(Dispatchers.Default).launch {
                        try {
                            while (isActive) {
                                simulator?.update(deltaMinutes = 1.0)
                                delay(1.seconds)
                            }
                        } catch (e: Exception) {
                            // Ignoruj błędy w tle
                        }
                    }
            }
        }
    }

    /**
     * Zwraca instancję symulatora
     */
    fun getSimulator(): EnvironmentSimulator? = simulator

    /**
     * Zatrzymuje aktualizacje w tle
     */
    fun stop() {
        updateJob?.cancel()
        updateJob = null
    }
}

fun Application.configureRouting() {
    install(Resources)

    SimulatorManager.initialize(this)

    routing {
        get("/") {
            call.respondText("Hello World!")
        }

        route("/api/environment") {
            get("/state") {
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                call.respond(simulator.getCurrentState())
            }

            get("/events") {
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                call.respond(simulator.getEvents())
            }

            get("/rooms") {
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                call.respond(simulator.getCurrentState().rooms)
            }

            get("/rooms/{roomId}") {
                val roomId = call.parameters["roomId"]
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                val room = simulator.getCurrentState().rooms.find { it.id == roomId }
                if (room != null) {
                    call.respond(room)
                } else {
                    call.respond(mapOf("error" to "Room not found"))
                }
            }

            get("/temperature") {
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                val state = simulator.getCurrentState()
                call.respond(
                    mapOf(
                        "externalTemperature" to state.externalTemperature,
                        "rooms" to
                            state.rooms.map { room ->
                                mapOf(
                                    "roomId" to room.id,
                                    "roomName" to room.name,
                                    "temperature" to room.temperatureSensor.temperature,
                                )
                            },
                    ),
                )
            }

            get("/motion") {
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                val state = simulator.getCurrentState()
                call.respond(
                    state.rooms.map { room ->
                        mapOf(
                            "roomId" to room.id,
                            "roomName" to room.name,
                            "motionDetected" to room.motionSensor.motionDetected,
                            "peopleCount" to room.peopleCount,
                            "lastMotionTime" to room.motionSensor.lastMotionTime,
                        )
                    },
                )
            }

            get("/devices") {
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                val state = simulator.getCurrentState()
                val allDevices = mutableListOf<Map<String, Any>>()

                state.rooms.forEach { room ->
                    room.lights.forEach { light ->
                        allDevices.add(
                            mapOf(
                                "id" to light.id,
                                "type" to "light",
                                "roomId" to room.id,
                                "roomName" to room.name,
                                "state" to light.state.name,
                                "brightness" to light.brightness,
                            ),
                        )
                    }

                    room.printer?.let { printer ->
                        allDevices.add(
                            mapOf(
                                "id" to printer.id,
                                "type" to "printer",
                                "roomId" to room.id,
                                "roomName" to room.name,
                                "state" to printer.state.name,
                                "tonerLevel" to printer.tonerLevel,
                                "paperLevel" to printer.paperLevel,
                            ),
                        )
                    }

                    room.blinds?.let { blinds ->
                        allDevices.add(
                            mapOf(
                                "id" to blinds.id,
                                "type" to "blinds",
                                "roomId" to room.id,
                                "roomName" to room.name,
                                "state" to blinds.state.name,
                            ),
                        )
                    }
                }

                call.respond(allDevices)
            }

            // Endpointy kontroli światła
            route("/devices/light/{lightId}") {
                get {
                    val lightId = call.parameters["lightId"]
                    val simulator =
                        SimulatorManager.getSimulator()
                            ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                    
                    val light = simulator.getLight(lightId ?: "")
                    if (light != null) {
                        call.respond(light)
                    } else {
                        call.respond(mapOf("error" to "Light not found"))
                    }
                }

                post("/control") {
                    val lightId = call.parameters["lightId"] ?: ""
                    val simulator =
                        SimulatorManager.getSimulator()
                            ?: return@post call.respond(mapOf("error" to "Simulator not initialized"))
                    
                    try {
                        val bodyText = call.receiveText()
                        println("Light control request for $lightId: $bodyText")
                        
                        // Prosty parsing JSON
                        val stateMatch = Regex(""""state"\s*:\s*"(\w+)"""").find(bodyText)
                        val brightnessMatch = Regex(""""brightness"\s*:\s*(\d+)""").find(bodyText)
                        
                        val state = stateMatch?.groupValues?.get(1)
                        val brightness = brightnessMatch?.groupValues?.get(1)?.toIntOrNull()
                        
                        println("Parsed - state: $state, brightness: $brightness")
                        
                        when (state) {
                            "ON" -> {
                                val success = simulator.setLightState(lightId, DeviceState.ON, brightness)
                                if (success) {
                                    call.respond(mapOf(
                                        "success" to true, 
                                        "message" to "Light turned on" + (brightness?.let { " with brightness $it%" } ?: "")
                                    ))
                                } else {
                                    call.respond(mapOf("success" to false, "error" to "Light not found or broken"))
                                }
                            }
                            "OFF" -> {
                                val success = simulator.setLightState(lightId, DeviceState.OFF)
                                if (success) {
                                    call.respond(mapOf("success" to true, "message" to "Light turned off"))
                                } else {
                                    call.respond(mapOf("success" to false, "error" to "Light not found"))
                                }
                            }
                            else -> {
                                call.respond(mapOf("success" to false, "error" to "Missing or invalid state: $state"))
                            }
                        }
                    } catch (e: Exception) {
                        println("Error in light control: ${e.message}")
                        e.printStackTrace()
                        call.respond(mapOf("success" to false, "error" to (e.message ?: "Unknown error")))
                    }
                }
            }
            
            // Endpointy kontroli drukarki
            route("/devices/printer/{printerId}") {
                get {
                    val printerId = call.parameters["printerId"]
                    val simulator =
                        SimulatorManager.getSimulator()
                            ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                    
                    val printer = simulator.getPrinter(printerId ?: "")
                    if (printer != null) {
                        call.respond(printer)
                    } else {
                        call.respond(mapOf("error" to "Printer not found"))
                    }
                }

                post("/control") {
                    val printerId = call.parameters["printerId"]
                    val simulator =
                        SimulatorManager.getSimulator()
                            ?: return@post call.respond(mapOf("error" to "Simulator not initialized"))
                    
                    try {
                        val request = call.receive<Map<String, Any>>()
                        val action = request["action"] as? String
                        
                        when (action) {
                            "turn_on" -> {
                                val success = simulator.setPrinterState(printerId ?: "", DeviceState.ON)
                                if (success) {
                                    call.respond(mapOf("success" to true, "message" to "Printer turned on"))
                                } else {
                                    call.respond(mapOf("success" to false, "error" to "Printer not found"))
                                }
                            }
                            "turn_off" -> {
                                val success = simulator.setPrinterState(printerId ?: "", DeviceState.OFF)
                                if (success) {
                                    call.respond(mapOf("success" to true, "message" to "Printer turned off"))
                                } else {
                                    call.respond(mapOf("success" to false, "error" to "Printer not found"))
                                }
                            }
                            "set_toner" -> {
                                val level = (request["level"] as? Number)?.toInt()
                                if (level != null) {
                                    val success = simulator.setPrinterTonerLevel(printerId ?: "", level)
                                    if (success) {
                                        call.respond(mapOf("success" to true, "message" to "Toner level set to $level"))
                                    } else {
                                        call.respond(mapOf("success" to false, "error" to "Printer not found"))
                                    }
                                } else {
                                    call.respond(mapOf("success" to false, "error" to "Invalid toner level"))
                                }
                            }
                            "set_paper" -> {
                                val level = (request["level"] as? Number)?.toInt()
                                if (level != null) {
                                    val success = simulator.setPrinterPaperLevel(printerId ?: "", level)
                                    if (success) {
                                        call.respond(mapOf("success" to true, "message" to "Paper level set to $level"))
                                    } else {
                                        call.respond(mapOf("success" to false, "error" to "Printer not found"))
                                    }
                                } else {
                                    call.respond(mapOf("success" to false, "error" to "Invalid paper level"))
                                }
                            }
                            else -> {
                                call.respond(mapOf("success" to false, "error" to "Unknown action: $action"))
                            }
                        }
                    } catch (e: Exception) {
                        call.respond(mapOf("success" to false, "error" to e.message))
                    }
                }
            }
            
            // Endpointy alertów od agentów
            route("/alerts") {
                get {
                    val simulator =
                        SimulatorManager.getSimulator()
                            ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                    call.respond(simulator.getAlerts())
                }
                
                post {
                    val simulator =
                        SimulatorManager.getSimulator()
                            ?: return@post call.respond(mapOf("error" to "Simulator not initialized"))
                    
                    try {
                        val request = call.receive<Map<String, Any>>()
                        val alertType = request["type"] as? String ?: return@post call.respond(
                            mapOf("success" to false, "error" to "Missing alert type")
                        )
                        @Suppress("UNCHECKED_CAST")
                        val data = request["data"] as? Map<String, Any> ?: emptyMap<String, Any>()
                        
                        // Obsługa alertów drukarek
                        val printerId = data["printer_id"] as? String ?: ""
                        val lightId = data["light_id"] as? String ?: ""
                        val roomName = data["room"] as? String ?: data["room_name"] as? String ?: ""
                        
                        val room = when {
                            printerId.isNotEmpty() -> simulator.getCurrentState().rooms.find { 
                                it.printer?.id == printerId 
                            }
                            lightId.isNotEmpty() -> simulator.getCurrentState().rooms.find { room ->
                                room.lights.any { it.id == lightId }
                            }
                            roomName.isNotEmpty() -> simulator.getCurrentState().rooms.find {
                                it.name == roomName
                            }
                            else -> null
                        }
                        
                        val tonerLevelValue = data.get("toner_level")
                        val paperLevelValue = data.get("paper_level")
                        val brightnessValue = data.get("brightness")
                        
                        val tonerLevel = when {
                            tonerLevelValue is Number -> tonerLevelValue.toString()
                            tonerLevelValue is String -> tonerLevelValue
                            else -> "0"
                        }
                        val paperLevel = when {
                            paperLevelValue is Number -> paperLevelValue.toString()
                            paperLevelValue is String -> paperLevelValue
                            else -> "0"
                        }
                        val brightness = when {
                            brightnessValue is Number -> brightnessValue.toString()
                            brightnessValue is String -> brightnessValue
                            else -> ""
                        }
                        
                        val deviceId = when {
                            printerId.isNotEmpty() -> printerId
                            lightId.isNotEmpty() -> lightId
                            else -> ""
                        }
                        
                        val alert = Alert(
                            id = "alert_${System.currentTimeMillis()}",
                            type = alertType,
                            printerId = deviceId,
                            roomId = room?.id,
                            roomName = room?.name ?: roomName,
                            message = when (alertType) {
                                // Alerty drukarek
                                "low_toner" -> "Niski poziom tonera w drukarence ${printerId}: ${tonerLevel}%"
                                "low_paper" -> "Niski poziom papieru w drukarence ${printerId}: ${paperLevel}%"
                                "printer_failure" -> "Awaria drukarki ${printerId} w sali ${room?.name ?: "nieznanej"}"
                                // Alerty świateł
                                "light_on" -> "Światło ${lightId} włączone w ${room?.name ?: roomName}" + 
                                    (if (brightness.isNotEmpty()) " (jasność: ${brightness}%)" else "")
                                "light_off" -> "Światło ${lightId} wyłączone w ${room?.name ?: roomName}"
                                "brightness_changed" -> "Zmiana jasności ${lightId} na ${brightness}% w ${room?.name ?: roomName}"
                                "light_failure" -> "Awaria światła ${lightId} w ${room?.name ?: roomName}"
                                "power_outage" -> "Awaria zasilania - urządzenia niedostępne"
                                else -> data["message"] as? String ?: "Alert: $alertType"
                            },
                            timestamp = java.time.LocalDateTime.now().format(
                                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
                            ),
                            severity = when (alertType) {
                                "printer_failure", "light_failure", "power_outage" -> "error"
                                "low_toner", "low_paper" -> "warning"
                                "light_on", "light_off", "brightness_changed" -> "info"
                                else -> "info"
                            }
                        )
                        
                        simulator.addAlert(alert)
                        
                        call.respond(mapOf("success" to true, "alert_id" to alert.id))
                    } catch (e: Exception) {
                        println("Error adding alert: ${e.message}")
                        e.printStackTrace()
                        call.respond(mapOf("success" to false, "error" to (e.message ?: "Unknown error")))
                    }
                }
            }
        }
    }
}

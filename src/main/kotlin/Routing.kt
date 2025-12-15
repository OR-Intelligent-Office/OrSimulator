package com.agh

import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds

/**
 * Singleton zarządzający instancją symulatora środowiska
 */
object SimulatorManager {
    private var simulator: EnvironmentSimulator? = null
    private var updateJob: Job? = null

    fun initialize(
        application: Application,
        startBackgroundUpdates: Boolean = true,
    ) {
        if (simulator == null) {
            simulator = EnvironmentSimulator(
                rooms = defaultRooms(),
                timeSpeedMultiplier = 1.0,
                failureProbability = 0.01,
            )

            if (startBackgroundUpdates) {
                updateJob = CoroutineScope(Dispatchers.Default).launch {
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

    fun getSimulator(): EnvironmentSimulator? = simulator

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
            call.respondText("OR Simulator API")
        }

        route("/api/environment") {
            get("/state") {
                val simulator = SimulatorManager.getSimulator()
                    ?: return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Simulator not initialized"))
                call.respond(simulator.getCurrentState())
            }

            get("/events") {
                val simulator = SimulatorManager.getSimulator()
                    ?: return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Simulator not initialized"))
                call.respond(simulator.getEvents())
            }

            get("/rooms") {
                val simulator = SimulatorManager.getSimulator()
                    ?: return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Simulator not initialized"))
                call.respond(simulator.getCurrentState().rooms)
            }

            get("/rooms/{roomId}") {
                val roomId = call.parameters["roomId"]
                val simulator = SimulatorManager.getSimulator()
                    ?: return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Simulator not initialized"))
                
                val room = simulator.getCurrentState().rooms.find { it.id == roomId }
                if (room != null) {
                    call.respond(room)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Room not found"))
                }
            }

            get("/temperature") {
                val simulator = SimulatorManager.getSimulator()
                    ?: return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Simulator not initialized"))
                val state = simulator.getCurrentState()
                call.respond(TemperatureResponse(
                    externalTemperature = state.externalTemperature,
                    rooms = state.rooms.map { room ->
                        RoomTemperature(
                            roomId = room.id,
                            roomName = room.name,
                            temperature = room.temperatureSensor.temperature
                        )
                    }
                ))
            }

            get("/motion") {
                val simulator = SimulatorManager.getSimulator()
                    ?: return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Simulator not initialized"))
                val state = simulator.getCurrentState()
                call.respond(state.rooms.map { room ->
                    RoomMotion(
                        roomId = room.id,
                        roomName = room.name,
                        motionDetected = room.motionSensor.motionDetected,
                        peopleCount = room.peopleCount,
                        lastMotionTime = room.motionSensor.lastMotionTime
                    )
                })
            }

            get("/devices") {
                val simulator = SimulatorManager.getSimulator()
                    ?: return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Simulator not initialized"))
                val state = simulator.getCurrentState()
                val allDevices = mutableListOf<DeviceInfo>()

                state.rooms.forEach { room ->
                    room.lights.forEach { light ->
                        allDevices.add(DeviceInfo(
                            id = light.id,
                            type = "light",
                            roomId = room.id,
                            roomName = room.name,
                            state = light.state.name,
                            brightness = light.brightness
                        ))
                    }

                    room.printer?.let { printer ->
                        allDevices.add(DeviceInfo(
                            id = printer.id,
                            type = "printer",
                            roomId = room.id,
                            roomName = room.name,
                            state = printer.state.name,
                            tonerLevel = printer.tonerLevel,
                            paperLevel = printer.paperLevel
                        ))
                    }

                    room.blinds?.let { blinds ->
                        allDevices.add(DeviceInfo(
                            id = blinds.id,
                            type = "blinds",
                            roomId = room.id,
                            roomName = room.name,
                            state = blinds.state.name
                        ))
                    }
                }

                call.respond(allDevices)
            }

            // Endpointy kontroli światła
            route("/devices/light/{lightId}") {
                get {
                    val lightId = call.parameters["lightId"]
                    val simulator = SimulatorManager.getSimulator()
                        ?: return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Simulator not initialized"))
                    
                    val light = simulator.getLight(lightId ?: "")
                    if (light != null) {
                        call.respond(light)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Light not found"))
                    }
                }

                post("/control") {
                    val lightId = call.parameters["lightId"] ?: ""
                    val simulator = SimulatorManager.getSimulator()
                        ?: return@post call.respond(HttpStatusCode.InternalServerError, ApiResponse(false, error = "Simulator not initialized"))
                    
                    try {
                        val bodyText = call.receiveText()
                        println("Light control request for $lightId: $bodyText")
                        
                        val stateMatch = Regex(""""state"\s*:\s*"(\w+)"""").find(bodyText)
                        val brightnessMatch = Regex(""""brightness"\s*:\s*(\d+)""").find(bodyText)
                        
                        val state = stateMatch?.groupValues?.get(1)
                        val brightness = brightnessMatch?.groupValues?.get(1)?.toIntOrNull()
                        
                        println("Parsed - state: $state, brightness: $brightness")
                        
                        when (state) {
                            "ON" -> {
                                val success = simulator.setLightState(lightId, DeviceState.ON, brightness)
                                if (success) {
                                    val msg = "Light turned on" + (brightness?.let { " with brightness $it%" } ?: "")
                                    call.respond(ApiResponse(true, message = msg))
                                } else {
                                    call.respond(ApiResponse(false, error = "Light not found or broken"))
                                }
                            }
                            "OFF" -> {
                                val success = simulator.setLightState(lightId, DeviceState.OFF)
                                if (success) {
                                    call.respond(ApiResponse(true, message = "Light turned off"))
                                } else {
                                    call.respond(ApiResponse(false, error = "Light not found"))
                                }
                            }
                            else -> {
                                call.respond(ApiResponse(false, error = "Missing or invalid state: $state"))
                            }
                        }
                    } catch (e: Exception) {
                        println("Error in light control: ${e.message}")
                        e.printStackTrace()
                        call.respond(ApiResponse(false, error = e.message ?: "Unknown error"))
                    }
                }
            }
            
            // Endpointy kontroli rolet
            route("/devices/blinds/{blindsId}") {
                get {
                    val blindsId = call.parameters["blindsId"]
                    val simulator = SimulatorManager.getSimulator()
                        ?: return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Simulator not initialized"))
                    
                    val blinds = simulator.getBlinds(blindsId ?: "")
                    if (blinds != null) {
                        call.respond(blinds)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Blinds not found"))
                    }
                }

                post("/control") {
                    val blindsId = call.parameters["blindsId"] ?: ""
                    val simulator = SimulatorManager.getSimulator()
                        ?: return@post call.respond(HttpStatusCode.InternalServerError, ApiResponse(false, error = "Simulator not initialized"))
                    
                    try {
                        val bodyText = call.receiveText()
                        println("Blinds control request for $blindsId: $bodyText")
                        
                        val stateMatch = Regex(""""state"\s*:\s*"(\w+)"""").find(bodyText)
                        val stateStr = stateMatch?.groupValues?.get(1)
                        
                        println("Parsed - state: $stateStr")
                        
                        when (stateStr?.uppercase()) {
                            "OPEN" -> {
                                val success = simulator.setBlindsState(blindsId, BlindState.OPEN)
                                if (success) {
                                    call.respond(ApiResponse(true, message = "Blinds opened"))
                                } else {
                                    call.respond(ApiResponse(false, error = "Blinds not found"))
                                }
                            }
                            "CLOSED" -> {
                                val success = simulator.setBlindsState(blindsId, BlindState.CLOSED)
                                if (success) {
                                    call.respond(ApiResponse(true, message = "Blinds closed"))
                                } else {
                                    call.respond(ApiResponse(false, error = "Blinds not found"))
                                }
                            }
                            else -> {
                                call.respond(ApiResponse(false, error = "Missing or invalid state: $stateStr"))
                            }
                        }
                    } catch (e: Exception) {
                        println("Error in blinds control: ${e.message}")
                        e.printStackTrace()
                        call.respond(ApiResponse(false, error = e.message ?: "Unknown error"))
                    }
                }
            }
            
            // Endpointy kontroli drukarki
            route("/devices/printer/{printerId}") {
                get {
                    val printerId = call.parameters["printerId"]
                    val simulator = SimulatorManager.getSimulator()
                        ?: return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Simulator not initialized"))
                    
                    val printer = simulator.getPrinter(printerId ?: "")
                    if (printer != null) {
                        call.respond(printer)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Printer not found"))
                    }
                }

                post("/control") {
                    val printerId = call.parameters["printerId"] ?: ""
                    val simulator = SimulatorManager.getSimulator()
                        ?: return@post call.respond(HttpStatusCode.InternalServerError, ApiResponse(false, error = "Simulator not initialized"))
                    
                    try {
                        val bodyText = call.receiveText()
                        println("Printer control request for $printerId: $bodyText")
                        
                        val actionMatch = Regex(""""action"\s*:\s*"(\w+)"""").find(bodyText)
                        val levelMatch = Regex(""""level"\s*:\s*(\d+)""").find(bodyText)
                        
                        val action = actionMatch?.groupValues?.get(1)
                        val level = levelMatch?.groupValues?.get(1)?.toIntOrNull()
                        
                        println("Parsed - action: $action, level: $level")
                        
                        when (action) {
                            "turn_on" -> {
                                val success = simulator.setPrinterState(printerId, DeviceState.ON)
                                if (success) {
                                    call.respond(ApiResponse(true, message = "Printer turned on"))
                                } else {
                                    val printer = simulator.getPrinter(printerId)
                                    val errorMsg = when {
                                        printer == null -> "Printer not found"
                                        printer.state == DeviceState.BROKEN -> "Cannot turn on: printer is broken"
                                        simulator.getCurrentState().powerOutage -> "Cannot turn on: power outage"
                                        printer.tonerLevel == 0 || printer.paperLevel == 0 -> 
                                            "Cannot turn on: insufficient resources"
                                        else -> "Unknown error"
                                    }
                                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, error = errorMsg))
                                }
                            }
                            "turn_off" -> {
                                val success = simulator.setPrinterState(printerId, DeviceState.OFF)
                                if (success) {
                                    call.respond(ApiResponse(true, message = "Printer turned off"))
                                } else {
                                    call.respond(ApiResponse(false, error = "Printer not found"))
                                }
                            }
                            "set_toner" -> {
                                if (level != null) {
                                    val success = simulator.setPrinterTonerLevel(printerId, level)
                                    if (success) {
                                        call.respond(ApiResponse(true, message = "Toner level set to $level"))
                                    } else {
                                        call.respond(ApiResponse(false, error = "Printer not found"))
                                    }
                                } else {
                                    call.respond(ApiResponse(false, error = "Invalid toner level"))
                                }
                            }
                            "set_paper" -> {
                                if (level != null) {
                                    val success = simulator.setPrinterPaperLevel(printerId, level)
                                    if (success) {
                                        call.respond(ApiResponse(true, message = "Paper level set to $level"))
                                    } else {
                                        call.respond(ApiResponse(false, error = "Printer not found"))
                                    }
                                } else {
                                    call.respond(ApiResponse(false, error = "Invalid paper level"))
                                }
                            }
                            else -> {
                                call.respond(ApiResponse(false, error = "Unknown action: $action"))
                            }
                        }
                    } catch (e: Exception) {
                        println("Error in printer control: ${e.message}")
                        e.printStackTrace()
                        call.respond(HttpStatusCode.InternalServerError, ApiResponse(false, error = e.message ?: "Unknown error"))
                    }
                }
            }
            
            // Endpointy alertów
            route("/alerts") {
                get {
                    val simulator = SimulatorManager.getSimulator()
                        ?: return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Simulator not initialized"))
                    call.respond(simulator.getAlerts())
                }
                
                post {
                    val simulator = SimulatorManager.getSimulator()
                        ?: return@post call.respond(HttpStatusCode.InternalServerError, ApiResponse(false, error = "Simulator not initialized"))
                    
                    try {
                        val bodyText = call.receiveText()
                        println("Alert request: $bodyText")
                        
                        // Parsowanie JSON ręcznie
                        val typeMatch = Regex(""""type"\s*:\s*"([^"]+)"""").find(bodyText)
                        val alertType = typeMatch?.groupValues?.get(1) ?: "unknown"
                        
                        val printerIdMatch = Regex(""""printer_id"\s*:\s*"([^"]+)"""").find(bodyText)
                        val printerId = printerIdMatch?.groupValues?.get(1) ?: ""
                        
                        val lightIdMatch = Regex(""""light_id"\s*:\s*"([^"]+)"""").find(bodyText)
                        val lightId = lightIdMatch?.groupValues?.get(1) ?: ""
                        
                        val roomMatch = Regex(""""room"\s*:\s*"([^"]+)"""").find(bodyText)
                        val roomFromRequest = roomMatch?.groupValues?.get(1) ?: ""
                        
                        val messageMatch = Regex(""""message"\s*:\s*"([^"]+)"""").find(bodyText)
                        val customMessage = messageMatch?.groupValues?.get(1) ?: ""
                        
                        val tonerMatch = Regex(""""toner_level"\s*:\s*(\d+)""").find(bodyText)
                        val tonerLevel = tonerMatch?.groupValues?.get(1) ?: "0"
                        
                        val paperMatch = Regex(""""paper_level"\s*:\s*(\d+)""").find(bodyText)
                        val paperLevel = paperMatch?.groupValues?.get(1) ?: "0"
                        
                        // Znajdź pokój
                        val room = when {
                            printerId.isNotEmpty() -> simulator.getCurrentState().rooms.find { 
                                it.printer?.id == printerId 
                            }
                            lightId.isNotEmpty() -> simulator.getCurrentState().rooms.find { r ->
                                r.lights.any { it.id == lightId }
                            }
                            roomFromRequest.isNotEmpty() -> simulator.getCurrentState().rooms.find {
                                it.name == roomFromRequest
                            }
                            else -> null
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
                            roomName = room?.name ?: roomFromRequest,
                            message = when {
                                customMessage.isNotEmpty() -> customMessage
                                alertType == "low_toner" -> "Niski poziom tonera w drukarce $printerId: $tonerLevel%"
                                alertType == "low_paper" -> "Niski poziom papieru w drukarce $printerId: $paperLevel%"
                                alertType == "printer_failure" -> "Awaria drukarki $printerId w sali ${room?.name ?: "nieznanej"}"
                                alertType == "light_failure" -> "Awaria światła $lightId w ${room?.name ?: roomFromRequest}"
                                alertType == "light_repaired" -> "Światło $lightId w ${room?.name ?: roomFromRequest} naprawione"
                                alertType == "power_outage" -> "Awaria zasilania - urządzenia niedostępne"
                                else -> "Alert: $alertType"
                            },
                            timestamp = java.time.LocalDateTime.now().format(
                                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
                            ),
                            severity = when (alertType) {
                                "printer_failure", "light_failure", "power_outage" -> "error"
                                "low_toner", "low_paper" -> "warning"
                                "light_repaired" -> "info"
                                else -> "info"
                            }
                        )
                        
                        simulator.addAlert(alert)
                        println("Alert dodany: ${alert.type} - ${alert.message}")
                        call.respond(ApiResponse(true, alertId = alert.id))
                        
                    } catch (e: Exception) {
                        println("Error adding alert: ${e.message}")
                        e.printStackTrace()
                        call.respond(ApiResponse(false, error = e.message ?: "Unknown error"))
                    }
                }
            }
            
            // Endpointy kontroli ogrzewania
            route("/heating") {
                get {
                    val simulator = SimulatorManager.getSimulator()
                        ?: return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Simulator not initialized"))
                    val state = simulator.getCurrentState()
                    call.respond(mapOf("isHeating" to state.isHeating))
                }
                
                post("/control") {
                    val simulator = SimulatorManager.getSimulator()
                        ?: return@post call.respond(HttpStatusCode.InternalServerError, ApiResponse(false, error = "Simulator not initialized"))
                    
                    try {
                        val bodyText = call.receiveText()
                        println("Heating control request: $bodyText")
                        
                        val isHeatingMatch = Regex(""""isHeating"\s*:\s*(true|false)""").find(bodyText)
                        val isHeatingStr = isHeatingMatch?.groupValues?.get(1)
                        
                        println("Parsed - isHeating: $isHeatingStr")
                        
                        when (isHeatingStr) {
                            "true" -> {
                                simulator.setHeating(true)
                                call.respond(ApiResponse(true, message = "Heating turned on"))
                            }
                            "false" -> {
                                simulator.setHeating(false)
                                call.respond(ApiResponse(true, message = "Heating turned off"))
                            }
                            else -> {
                                call.respond(ApiResponse(false, error = "Missing or invalid isHeating: $isHeatingStr"))
                            }
                        }
                    } catch (e: Exception) {
                        println("Error in heating control: ${e.message}")
                        e.printStackTrace()
                        call.respond(ApiResponse(false, error = e.message ?: "Unknown error"))
                    }
                }
            }
        }
    }
}

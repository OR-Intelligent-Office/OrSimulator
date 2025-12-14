# OrSimulator - Symulator Inteligentnego Biura

Symulator środowiska inteligentnego biura z obsługą urządzeń IoT, agentów i wizualizacji w czasie rzeczywistym.

## Opis

OrSimulator symuluje środowisko inteligentnego biura z następującymi elementami:
- **Pokoje** z urządzeniami (światła, drukarki, rolety, czujniki)
- **Automatyczne zarządzanie urządzeniami** na podstawie wykrytego ruchu
- **Symulacja zużycia zasobów** (toner, papier w drukarkach)
- **System alertów** od agentów
- **Zdarzenia środowiskowe** (awarie, zmiany temperatury, utrata zasilania)

## Czas symulacji

- **1 minuta symulacji = 1 sekunda rzeczywista**
- Symulator aktualizuje się co sekundę rzeczywistą
- Godzina symulacji = 60 sekund rzeczywistych

## Port

Symulator działa na porcie **8080** (domyślnie).

## Funkcje EnvironmentSimulator

### Funkcje publiczne

#### `getCurrentState(): EnvironmentState`
Zwraca aktualny stan środowiska (wszystkie pokoje, urządzenia, parametry).

#### `getEvents(): List<EnvironmentEvent>`
Zwraca listę wszystkich zdarzeń środowiskowych (ruchy, awarie, zmiany temperatury, itp.).

#### `clearEvents()`
Czyści listę zdarzeń środowiskowych.

#### `addAlert(alert: Alert)`
Dodaje alert od agenta do systemu. Przechowuje maksymalnie 100 ostatnich alertów.

#### `getAlerts(): List<Alert>`
Zwraca listę wszystkich alertów od agentów.

#### `clearAlerts()`
Czyści listę alertów.

#### `update(deltaMinutes: Double = 1.0)`
Główna funkcja aktualizująca symulację środowiska. Wywoływana co sekundę rzeczywistą (co minutę symulacji). Aktualizuje wszystkie aspekty środowiska: ruch ludzi, temperatury, awarie, zdarzenia, stany urządzeń i zasoby drukarek.

#### `setTimeSpeedMultiplier(multiplier: Double)`
Ustawia mnożnik prędkości symulacji (niezaimplementowane).

#### `setExternalTemperature(temp: Double)`
Ustawia temperaturę zewnętrzną.

#### `setHeating(on: Boolean)`
Włącza/wyłącza ogrzewanie.

#### `setPrinterState(printerId: String, state: DeviceState): Boolean`
Ustawia stan drukarki (ON/OFF/BROKEN). Nie pozwala włączyć drukarki jeśli toner lub papier = 0%. Zwraca `true` jeśli operacja się powiodła, `false` jeśli drukarka nie została znaleziona lub brak zasobów.

#### `setPrinterTonerLevel(printerId: String, level: Int): Boolean`
Ustawia poziom tonera drukarki (0-100%). Automatycznie zapisuje czas wyczerpania jeśli poziom spadł do 0%. Zwraca `true` jeśli operacja się powiodła, `false` jeśli drukarka nie została znaleziona.

#### `setPrinterPaperLevel(printerId: String, level: Int): Boolean`
Ustawia poziom papieru drukarki (0-100%). Automatycznie zapisuje czas wyczerpania jeśli poziom spadł do 0%. Zwraca `true` jeśli operacja się powiodła, `false` jeśli drukarka nie została znaleziona.

#### `getPrinter(printerId: String): PrinterDevice?`
Zwraca stan drukarki o podanym ID. Zwraca `PrinterDevice` jeśli znaleziono, `null` w przeciwnym razie.

### Funkcje prywatne

#### `updatePeopleMovement(currentTime: LocalDateTime)`
Symuluje ruch ludzi w pokojach na podstawie godzin pracy. Prawdopodobieństwo wykrycia ruchu zależy od pory dnia (wyższe w godzinach pracy 8-16). Liczba osób w pokoju: 1-4 gdy wykryto ruch, 0 gdy brak ruchu.

#### `updateTemperatures()`
Aktualizuje temperatury w pokojach. Temperatura dąży do wartości docelowej (22°C jeśli ogrzewanie włączone, temperatura zewnętrzna jeśli wyłączone). Zawiera losowy szum i jest ograniczona do zakresu 15-28°C.

#### `checkDeviceFailures(currentTime: LocalDateTime)`
Sprawdza i generuje losowe awarie urządzeń. Może powodować awarie: świateł, drukarek, czujników ruchu. Prawdopodobieństwo awarii zależy od parametru `failureProbability`.

#### `generateRandomEvents(currentTime: LocalDateTime)`
Generuje losowe zdarzenia środowiskowe:
- Nagłe zmiany temperatury zewnętrznej (1% szansy)
- Zmiany natężenia światła dziennego - zachmurzenie (2% szansy)
- Tymczasowa utrata zasilania (0.5% szansy) i jego przywrócenie (10% szansy)

#### `updateDeviceStates(currentTime: LocalDateTime)`
Aktualizuje stany urządzeń w pokojach na podstawie wykrytego ruchu:
- **Światła**: włączają się automatycznie przy wykryciu ruchu, wyłączają po braku ruchu
- **Drukarki**: włączają się przy wykryciu ruchu (tylko jeśli zasoby > 0), wyłączają po braku ruchu
- **Zużywa zasoby drukarki** gdy jest włączona (toner i papier)
- **Jeśli jeden zasób = 0%**, drugi przestaje się zużywać

#### `replenishPrinterResources()`
Automatyczne uzupełnianie zasobów drukarki. **Oba warunki muszą być spełnione jednocześnie:**
1. Musi minąć godzina symulacji (60 sekund rzeczywistych)
2. Ktoś musi być w pokoju (`peopleCount > 0`)

W symulacji 1 minuta = 1 sekunda rzeczywista, więc godzina symulacji = 60 sekund rzeczywistych. Sprawdzane niezależnie od stanu drukarki.

#### `createInitialState(): EnvironmentState`
Tworzy początkowy stan środowiska na podstawie konfiguracji pokoi. Inicjalizuje wszystkie urządzenia w stanie OFF, losowe poziomy zasobów drukarek (50-100%).

## API Endpoints

### Informacje o środowisku

#### `GET /api/environment/state`
Zwraca pełny stan środowiska (wszystkie pokoje, urządzenia, parametry).

#### `GET /api/environment/events`
Zwraca listę wszystkich zdarzeń środowiskowych.

#### `GET /api/environment/rooms`
Zwraca listę wszystkich pokoi.

#### `GET /api/environment/rooms/{roomId}`
Zwraca szczegóły konkretnego pokoju.

#### `GET /api/environment/temperature`
Zwraca informacje o temperaturach (zewnętrzna i w pokojach).

#### `GET /api/environment/motion`
Zwraca informacje o wykrytym ruchu we wszystkich pokojach.

#### `GET /api/environment/devices`
Zwraca listę wszystkich urządzeń we wszystkich pokojach.

### Kontrola drukarek

#### `GET /api/environment/devices/printer/{printerId}`
Zwraca stan konkretnej drukarki.

#### `POST /api/environment/devices/printer/{printerId}/control`
Kontroluje drukarkę. Akcje:
- `turn_on` - włącza drukarkę (tylko jeśli toner > 0% i papier > 0%)
- `turn_off` - wyłącza drukarkę
- `set_toner` - ustawia poziom tonera (0-100%)
- `set_paper` - ustawia poziom papieru (0-100%)

Przykład:
```json
{
  "action": "set_toner",
  "level": 50
}
```

### Alerty

#### `GET /api/environment/alerts`
Zwraca listę wszystkich alertów od agentów.

#### `POST /api/environment/alerts`
Dodaje nowy alert od agenta.

Przykład:
```json
{
  "type": "low_toner",
  "data": {
    "printer_id": "printer_208",
    "toner_level": 15
  }
}
```

## Logika działania drukarek

### Automatyczne włączanie/wyłączanie
- Drukarka włącza się automatycznie gdy wykryto ruch w pokoju
- Drukarka wyłącza się automatycznie gdy brak ruchu
- **Nie można włączyć** jeśli toner = 0% lub papier = 0%

### Zużywanie zasobów
- Gdy drukarka jest włączona, zużywa toner i papier
- Intensywność zużycia zależy od obecności ludzi:
  - Z ludźmi w pokoju: 0.5-1.5% tonera i 1.0-2.5% papieru na minutę symulacji
  - Bez ludzi: 0.15-0.45% tonera i 0.3-0.75% papieru na minutę symulacji
- **Jeśli jeden zasób = 0%**, drugi przestaje się zużywać
- Gdy zasoby się wyczerpią, drukarka automatycznie się wyłącza

### Automatyczne uzupełnianie zasobów
Zasoby (toner i papier) są automatycznie uzupełniane do 100% gdy:
1. **Minęła godzina symulacji** (60 sekund rzeczywistych) od wyczerpania
2. **I ktoś jest w pokoju** (`peopleCount > 0`)

Oba warunki muszą być spełnione jednocześnie.

## Konfiguracja pokoi

Domyślnie symulator zawiera 4 pokoje:
- **Sala 208** - 2 światła, drukarka, rolety
- **Sala 209** - 1 światło, drukarka, rolety
- **Sala 210** - 3 światła, rolety (bez drukarki)
- **Biuro 101** - 1 światło, drukarka, rolety

## Budowanie i uruchamianie

```bash
# Kompilacja
./gradlew compileKotlin

# Uruchomienie
./gradlew run

# Pełny build
./gradlew build
```

Po uruchomieniu symulator będzie dostępny pod adresem: `http://localhost:8080`

## Zależności

- Kotlin
- Ktor (framework HTTP)
- kotlinx.serialization (JSON)
- kotlinx.coroutines (asynchroniczność)

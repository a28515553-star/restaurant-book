# دفتر المطعم: Design System Rules for Figma MCP

Native **Android** app (Kotlin + Jetpack Compose, Material 3, Room). There is no web code, CSS, npm, Storybook or token pipeline. When implementing a Figma design, **translate it to Compose** and reuse the existing components and theme described below.

- UI language: **Arabic, always right-to-left** (forced in `MainActivity.kt`, regardless of phone language).
- Offline only; all data lives in a local Room database.
- All user-facing strings are hardcoded Arabic literals in composables (only `app_name` is in `res/values/strings.xml`).

## 1. Design System Structure

### 1.1 Token Definitions
There is no token file or transformation system. Tokens are Kotlin values in `app/src/main/java/com/ibrahim/restaurantbook/ui/Theme.kt`:

```kotlin
val ProfitGreen = Color(0xFF2E7D32)   // income, profit, primary
val LossRed = Color(0xFFC62828)       // loss, destructive
val ExpenseOrange = Color(0xFFE65100) // expenses
val SalaryBlue = Color(0xFF1565C0)    // salaries

private val colors = lightColorScheme(
    primary = Color(0xFF2E7D32), onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9), onPrimaryContainer = Color(0xFF0B3D0E),
    secondary = Color(0xFF6D4C41),
    background = Color(0xFFFAFAF7), surface = Color(0xFFFAFAF7),
    surfaceVariant = Color(0xFFEDEDE6),
)

@Composable
fun RestaurantTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = colors, content = content)
```

- **Colors:** light scheme only (no dark theme, no dynamic color). Map Figma color styles/variables to a `MaterialTheme.colorScheme.*` role first; otherwise use the four semantic constants above. Add a new constant to `Theme.kt` only for a genuinely new semantic color. Never inline hex in screens.
- **Typography:** the Material 3 default type scale with the system font. No custom `Typography` or font files. Use `MaterialTheme.typography.*` (`titleMedium`, `headlineSmall`, `labelLarge`, `bodySmall`, ...) plus `fontWeight = FontWeight.Bold` where needed.
- **Shapes:** Material 3 defaults (cards, dialogs, FAB). No custom `Shapes`.
- **Spacing:** raw `dp` literals, with no spacing tokens. Observed values: 4, 8, 12, 16 (list `contentPadding` is 12 with 96 bottom so the FAB doesn't cover rows). Round Figma spacing to these.
- **Tint pattern:** banners use a semantic color at 10% alpha, e.g. `color.copy(alpha = 0.10f)`.
- Legacy XML colors: `res/values/colors.xml` (`ic_launcher_background` #2E7D32) and `res/values/themes.xml` (status bar #2E7D32, window background #FAFAF7). Keep these in sync with `Theme.kt` if the brand colors change.

### 1.2 Component Library
Shared components are in `ui/Components.kt`. **Reuse these before writing new UI:**

| Component | Purpose |
|---|---|
| `MonthBar(month, onPrevious, onNext)` | Month switcher under the top bar (`primaryContainer` surface) |
| `TotalBanner(label, amount, color)` | Tinted card with a label and a bold amount |
| `DayHeader(epochDay, total)` | Day group header with the daily total |
| `EntryRow(title, subtitle, amount, amountColor, onClick, onDelete)` | List row card (`surfaceVariant`) with a delete icon |
| `EmptyState(text)` | Centered empty-list message |
| `AmountField`, `NoteField`, `DateField` | Form fields (outlined text fields, a date picker dialog) |
| `EntryDialog(title, onDismiss, onSave, content)` | Add/edit `AlertDialog` shell with Save/Cancel |
| `ConfirmDeleteDialog(message, onConfirm, onDismiss)` | Delete confirmation, red "حذف" button |

- Architecture: stateless composables with hoisted state and callbacks. Screens (`ExpensesScreen`, `SalariesScreen`, `IncomeScreen`, `ReportScreen`) take the shared `MainViewModel`, collect with `collectAsStateWithLifecycle()`, and keep dialog state in local `remember { mutableStateOf<T?>(null) }`.
- Screen pattern: `Box` > `LazyColumn` + `ExtendedFloatingActionButton` aligned `BottomEnd`; then `editing?.let { ... Dialog }` and `deleting?.let { ... ConfirmDeleteDialog }`. See `IncomeScreen.kt` as the reference.
- Navigation is a `Scaffold` with a `CenterAlignedTopAppBar` (primary background), a `MonthBar` and a 4-item `NavigationBar`, switched by an index in `MainActivity.kt`. There is no Navigation library.
- No Storybook or component docs. `ui-tooling-preview` is a dependency but no `@Preview` functions exist yet.

### 1.3 Frameworks & Libraries
- Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01), Material 3, `material-icons-core`
- Room 2.6.1 + KSP, `lifecycle-viewmodel-compose` 2.8.7, `activity-compose` 1.9.3
- Build: Gradle (Kotlin DSL) with AGP 8.7.3; minSdk 26, compileSdk/targetSdk 35, Java 17
- CI: `.github/workflows/build-apk.yml` runs `./gradlew assembleDebug` on pushes to `main` and publishes `restaurant-book.apk` to the `latest` release.
- Do not add new UI libraries (no Coil, Accompanist, Navigation, extended icons) without a clear need.

### 1.4 Asset Management
- No raster images, fonts, videos or CDN. The only assets are the launcher icon files: `res/drawable/ic_launcher_foreground.xml` (vector) and `res/mipmap-anydpi-v26/ic_launcher.xml` (adaptive icon on a green background).
- If a Figma design needs an image, export it as an Android **vector drawable** into `res/drawable/` and load it with `painterResource(R.drawable.name)`. Use snake_case file names. Prefer vectors over PNG. Do not hot-link Figma asset URLs.

### 1.5 Icon System
- Icons come from **Material Icons Core** (`androidx.compose.material.icons`), used as `Icon(Icons.Filled.Delete, contentDescription = ...)`. Directional icons use `Icons.AutoMirrored.Filled.*` so they flip in RTL.
- Currently used: `ShoppingCart`, `Person`, `AddCircle`, `AutoMirrored.List`, `Add`, `Delete`, `DateRange`, `AutoMirrored.KeyboardArrowLeft/Right`.
- The core set is small. If a Figma icon is not in it, export a vector drawable into `res/drawable/` as `ic_<name>.xml` (do not add `material-icons-extended`, since it is large).
- Icon `contentDescription` is Arabic, or `null` when decorative. Tint with theme colors (e.g. `colorScheme.outline`).

### 1.6 Styling Approach
- Pure Compose: `Modifier` chains with `Card`, `Surface`, `Row`, `Column`. No XML layouts, CSS or CSS-in-JS.
- Global styling is only `RestaurantTheme` (wrapped in `setContent`) and the XML theme in `res/values/themes.xml`.
- Responsive: phone-only portrait layout using `fillMaxWidth()`, `weight(1f)` and scrolling lists. There are no window-size classes or breakpoints. Convert Figma auto layout to `Row`/`Column` with `Arrangement.spacedBy(...)` and `padding(...)`.
- **RTL rules:** never hardcode left/right. Use `start`/`end` and `Alignment.BottomEnd`. Keep Figma mockups in RTL order (first item on the right). Dates and months use Arabic names via `Format.kt`; amounts use **Western digits** (`formatAmount`). Amount input accepts Arabic-Indic digits (`parseAmount`). Use these helpers rather than manual formatting.

### 1.7 Project Structure
```
app/src/main/java/com/ibrahim/restaurantbook/
  MainActivity.kt        entry, RTL, Scaffold + tabs
  data/                  Entities.kt, Daos.kt, AppDatabase.kt (Room)
  ui/
    Theme.kt             color tokens + RestaurantTheme
    Components.kt        shared composables
    Format.kt            amount/date formatting and parsing
    MainViewModel.kt     single shared ViewModel (month state + flows)
    *Screen.kt           one file per tab: Expenses, Salaries, Income, Report
app/src/main/res/        launcher icon, colors.xml, strings.xml, themes.xml
```
Feature organization is flat: one screen file per tab, shared pieces in `Components.kt`. Dates are stored as `LocalDate.toEpochDay()` longs.

## 2. Figma to Compose Workflow

1. Call `create_session` for the file, then `get_design_context` for the node (and `get_screenshot` for a visual reference). Treat the output (React/Tailwind-style) as a **reference only**. Do not paste it in.
2. Map each Figma element to an existing component in section 1.2 where possible; otherwise compose Material 3 primitives.
3. Map colors to `MaterialTheme.colorScheme` or `Theme.kt` constants, text to `MaterialTheme.typography` styles, and spacing to the dp values above.
4. Write the UI in Arabic, RTL-safe, with `start`/`end` and AutoMirrored icons. Put reusable pieces in `ui/Components.kt` and screen-specific ones as `private` composables in the screen file.
5. Verify with `./gradlew assembleDebug`. No tests exist yet.

## 3. Conventions
- Package `com.ibrahim.restaurantbook`; composables in PascalCase; screen-local helpers `private`.
- Money is `Double` formatted with `formatAmount`; never format with `String.format` or `toString()` for display.
- Positive/profit and income use `ProfitGreen`, loss and destructive actions use `LossRed`, expenses use `ExpenseOrange`, salaries use `SalaryBlue`.
- Deleting always goes through `ConfirmDeleteDialog`; add/edit goes through `EntryDialog`.

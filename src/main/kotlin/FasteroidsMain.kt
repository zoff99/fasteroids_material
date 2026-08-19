/**
 * # Fasteroids - Kotlin Compose Desktop Port
 *
 * ## Overview
 * This file contains the complete source code for "Fasteroids", a modern Kotlin Compose Desktop
 * port of a classic late-90s Java applet space shooter game. The game features asteroid dodging,
 * shooting, shield mechanics, weapon upgrades, and power-ups.
 *
 * ## Architecture
 * The code is structured into several main components:
 * 1. **Configuration Constants**: Global variables controlling game speed, window sizes, and mechanics.
 * 2. **Asset Managers**: `ImageAssets` and `SoundManager` handle loading graphics (Skia) and audio (Java Sound/MIDI).
 * 3. **Game Entities**: Data classes representing in-game objects (`Circle` for asteroids, `Shuttle`, `Shot`, `Goodie`).
 * 4. **Game State (`FasteroidsGameState`)**: The core logic engine. Manages the game loop, physics, collisions,
 *    input processing, and state transitions (menus, levels, game over). Marked `@Stable` for Compose optimizations.
 * 5. **Compose UI (`FasteroidsGame`)**: The rendering layer. Uses `Canvas` for drawing the game world and standard
 *    Compose components for the HUD (Heads-Up Display) and pause overlays.
 * 6. **Intro Window**: A secondary window displaying game instructions and credits.
 * 7. **Main Entry Point**: Sets up the Compose Desktop application window, initializes the game loop coroutine,
 *    and manages the application lifecycle.
 *
 * ## Debugging Tips
 * - **Game Speed**: Adjust `GAME_TICK_MS` to slow down or speed up the entire game loop.
 * - **Animations**: Tweak `ASTEROID_ANIM_TICKS` and `SHIELD_ANIM_TICKS` to change animation framerates.
 * - **Cheat Mode**: Set `DEBUG_IT = true` to enable infinite ammo and invincibility for testing collisions and levels.
 * - **Asset Loading**: The game includes a 3-second intentional delay before loading actual assets to showcase
 *   the procedural fallback graphics. If images/sounds don't load, check the `resourcesDir` path in `main()`.
 * - **Collisions**: Asteroid-Shuttle collisions use circular detection when shielded, and point-based AABB when unshielded.
 * - **Concurrency**: The game uses a fixed time-step loop. Entities are removed via deferred lists to prevent
 *   `ConcurrentModificationException` during iteration.
 *
 * ## Controls
 * - **Arrow Keys**: Move the shuttle
 * - **Spacebar**: Fire current weapon
 * - **S Key**: Activate shield
 * - **1-6 Keys**: Switch between available weapon levels
 */

// File: src/main/kotlin/Fasteroids.kt
@file:Suppress("FunctionName", "KotlinPrintToLogpoint", "PropertyName", "unused", "LocalVariableName", "ConstPropertyName", "DestructuringDeclaration", "SpellCheckingInspection", "HttpUrlsUsage")

// ============================================================================
// IMPORTS
// ============================================================================
// Compose UI components for building the game interface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

// Coroutines for async operations (game loop, image loading)
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap // Skia graphics engine for image decoding
import org.jetbrains.skia.Image
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

// Java MIDI API for background music
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequencer

// Math utilities
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds

// ============================================================================
// CONFIGURATION CONSTANTS
// ============================================================================

// Debug mode flag - when true, enables cheat features (infinite ammo, no damage)
const val DEBUG_IT = false // set "false" for release builds

// Showcase mode for CI runs to make nice screenshots without the need to press keys
const val DEMO_SHOWCASE_DEBUG_ONLY = false // set "false" for release builds


// initial scaling factor
val INIT_SCALE = if (DEMO_SHOWCASE_DEBUG_ONLY) 2.5f else 3.5f

//
// HINT: the original game was exactly 425 x 360 pixels in size
//
const val original_pixel_width = 425
const val original_pixel_height = 360

// Main game window dimensions
const val fasteroids_main_width = original_pixel_width // 850/2
const val fasteroids_main_height = original_pixel_height // 720/2

// Outer window dimensions (includes UI elements)
const val fasteroids_window_width = (fasteroids_main_width*2) - 80
const val fasteroids_window_height = (fasteroids_main_height*2)
const val ui = 50

// Intro window width
const val intro_window_width = 500

// We set this later to the "resources/common/" directory in the source tree
var resourcesDir: File? = null

// ============================================================================
// GAME SPEED CONFIGURATION
// ============================================================================

// HINT: increase this value to make the game slower, decrease to make it faster
// This controls the game loop tick rate (milliseconds per frame)
// Affects overall game speed: movement, shooting, collisions, etc.
const val GAME_TICK_MS = 30L

// HINT: increase this value to make asteroids spin slower, decrease to make them spin faster
// Controls how many game ticks before asteroid animation frame advances
// Higher value = slower spinning animation
const val ASTEROID_ANIM_TICKS = 10L

// HINT: increase this value to make shield animation slower and shield last longer
// Controls both:
// 1. How many game ticks before shield animation frame advances (visual rotation)
// 2. How many game ticks the shield stays active (duration)
// Higher value = slower animation AND longer shield duration
const val SHIELD_ANIM_TICKS = 8L

// Version information displayed in the game
const val Version_string = "v1.2.0 (Build 2026-0001)"
const val ver = "Fasteroids $Version_string (C)1998-99 Planet Web Team.\n" +
        "standalone Material 2026 Edition"

const val OSD_FONT_SIZE = 4

// Maximum number of laser upgrade levels (0-5)
const val max_diff_laser = 6

// Ammo consumption for each laser level (index 0-5)
// Higher laser levels consume more ammo per shot
val use_ammo = intArrayOf(1, 1, 1, 1, 1, 15)

// Level names displayed during level transitions
val level_name = arrayOf("Space", "Asteroid belt", "Nebula of Sorrow", "Quick Death")

// Total number of levels in the game (1-4)
const val max_level = 4

// Colors used for UI elements
val score_color = Color(190, 190, 190)
val ammo_color = Color(190, 190, 190)

// Alternating colors for text effects
val colls = arrayOf(Color.White, Color.Black, Color.White)

const val BG_BORDER_COLOR = 0xFF202020

// ============================================================================
// IMAGE ASSETS MANAGER
// ============================================================================

// ------------------ IMAGE ASSETS MANAGER ------------------

/**
 * Manages loading and caching of all game image assets.
 * Supports loading from both local filesystem (assets/pix/) and classpath resources.
 * Provides graceful fallback to procedural graphics if images fail to load.
 */
class ImageAssets {
    // Flag indicating whether all images have been loaded
    var isReady by mutableStateOf(false)

    // Image arrays for different game entities
    val shuttles = arrayOfNulls<ImageBitmap>(4)              // Shuttle frames (0-3: different orientations)
    val shuttleEx = arrayOfNulls<ImageBitmap>(6)             // Explosion frames (indices 1-5 used)
    val lasers0 = arrayOfNulls<ImageBitmap>(2)               // Basic laser frames
    val lasers1 = arrayOfNulls<ImageBitmap>(2)               // Enhanced laser frames
    val lasers2 = arrayOfNulls<ImageBitmap>(3)               // Rocket/super laser frames
    val shields = arrayOfNulls<ImageBitmap>(2)               // Shield animation frames
    val asteroids = Array(3) { arrayOfNulls<ImageBitmap>(8) } // 3 kinds × 8 frames each
    val goodies = Array(3) { arrayOfNulls<ImageBitmap>(4) }  // 3 types × 4 frames each

    /**
     * Loads all game images from assets.
     * Attempts to load from local filesystem first, then falls back to classpath resources.
     * Sets isReady to true when complete (even if some images failed to load).
     */
    fun load() {
        if (isReady) return
        try {
            // Load shuttle images (4 directional frames)
            for (i in 0..3) shuttles[i] = loadAssetImage("shuttle$i.gif")

            // Load shuttle explosion frames (5 frames, indices 1-5)
            for (i in 1..5) shuttleEx[i] = loadAssetImage("shuttleex$i.gif")

            // Load laser projectile frames for each weapon type
            for (i in 0..1) lasers0[i] = loadAssetImage("laser0-$i.gif")
            for (i in 0..1) lasers1[i] = loadAssetImage("laser1-$i.gif")
            for (i in 0..2) lasers2[i] = loadAssetImage("laser2-$i.gif")

            // Load shield animation frames (2 frames for rotation effect)
            for (i in 0..1) shields[i] = loadAssetImage("shield$i.gif")

            // Load asteroid images (3 different kinds, 8 animation frames each)
            for (k in 0..2) {
                for (f in 0..7) {
                    asteroids[k][f] = loadAssetImage("asteroid$k-$f.gif")
                }
            }

            // Load power-up/goodie images (3 types, 4 animation frames each)
            for (k in 0..2) {
                for (f in 0..3) {
                    goodies[k][f] = loadAssetImage("goodies$k-$f.gif")
                }
            }

            isReady = true
            println("✅ All game assets loaded successfully!")
        } catch (e: Exception) {
            println("⚠️ Image loading error: ${e.message}")
            isReady = true // Prevent infinite retry; missing images will gracefully use fallbacks
        }
    }

    /**
     * Attempts to load a single image asset.
     * First tries to load from local assets/pix/ directory (like original Java game).
     * Falls back to classpath resources if file not found.
     * Returns null if image cannot be loaded (will use procedural fallback graphics).
     *
     * @param filename The image filename (e.g., "shuttle0.gif")
     * @return ImageBitmap if loaded successfully, null otherwise
     */
    private fun loadAssetImage(filename: String): ImageBitmap? {
        return try {
            // 1. Try loading from the local assets/pix/ directory (like the original Java game)
            val file = File(resourcesDir, "assets/pix/$filename")
            if (file.exists()) {
                val bytes = file.readBytes()
                // Skia handles GIF, PNG, JPG seamlessly from byte arrays
                Image.makeFromEncoded(bytes).toComposeImageBitmap()
            } else {
                // 2. Fallback to standard Compose resource loading (src/main/resources/pix/)
                val stream = this::class.java.classLoader?.getResourceAsStream("pix/$filename")
                stream?.readAllBytes()?.decodeToImageBitmap()
            }
        } catch (e: Exception) {
            println("⚠️ Failed to load $filename: ${e.message}")
            null
        }
    }
}

// ============================================================================
// SOUND ASSETS MANAGER
// ============================================================================

/**
 * Manages loading and playing of all game sound effects.
 * Supports loading from both local filesystem (assets/sound/) and classpath resources.
 * Also handles MIDI background music playback.
 */
class SoundManager {
    var isReady by mutableStateOf(false)
    var soundEnabled by mutableStateOf(true)
    var midiEnabled by mutableStateOf(true)

    private var boomClip: Clip? = null
    private var bingClip: Clip? = null
    private var blstClip: Clip? = null
    private var xplsClip: Clip? = null
    private var xplaClip: Clip? = null

    private var shotSingleClip: Clip? = null
    private var shotRocketClip: Clip? = null
    private var shotDoubleClip: Clip? = null
    private var shotTripleClip: Clip? = null
    private var shotNrgClip: Clip? = null

    private val chgLaserClips = arrayOfNulls<Clip>(6)
    private var soundOnClip: Clip? = null
    private var soundOverClip: Clip? = null

    // MIDI sequencer for background music
    private var midiSequencer: Sequencer? = null

    fun load() {
        if (isReady) return
        try {
            boomClip = loadSound("boom.wav")
            bingClip = loadSound("bing.wav")
            blstClip = loadSound("blst.wav")
            xplsClip = loadSound("xpls.wav")
            xplaClip = loadSound("xpla.wav")

            shotSingleClip = loadSound("shot_single.wav")
            shotRocketClip = loadSound("shot_rocket.wav")
            shotDoubleClip = loadSound("shot_double.wav")
            shotTripleClip = loadSound("shot_triple.wav")
            shotNrgClip = loadSound("shot_nrg.wav")

            for (i in 0..5) {
                chgLaserClips[i] = loadSound("chg_laser$i.wav")
            }

            soundOnClip = loadSound("on.wav")
            soundOverClip = loadSound("over.wav")

            // Load MIDI background music
            loadMidi()

            isReady = true
            println("✅ All game sounds loaded successfully!")
        } catch (e: Exception) {
            println("⚠️ Sound loading error: ${e.message}")
            isReady = true // Prevent infinite retry; missing sounds will gracefully fail
        }
    }

    private fun loadSound(filename: String): Clip? {
        return try {
            val file = File(resourcesDir,"assets/sound/$filename")
            val stream = if (file.exists()) {
                AudioSystem.getAudioInputStream(file)
            } else {
                val resourceStream = this::class.java.classLoader?.getResourceAsStream(filename)
                if (resourceStream != null) AudioSystem.getAudioInputStream(resourceStream) else null
            }
            stream?.let {
                val clip = AudioSystem.getClip()
                clip.open(it)
                clip
            }
        } catch (e: Exception) {
            println("⚠️ Failed to load sound $filename: ${e.message}")
            null
        }
    }

    /**
     * Loads the MIDI background music file.
     * Attempts to load from local filesystem first, then falls back to classpath resources.
     * Sets up the sequencer to loop continuously when started.
     */
    private fun loadMidi() {
        try {
            val file = File(resourcesDir, "assets/sound/fasteroids.mid")
            val sequence = if (file.exists()) {
                MidiSystem.getSequence(file)
            } else {
                val resourceStream = this::class.java.classLoader?.getResourceAsStream("sound/fasteroids.mid")
                if (resourceStream != null) MidiSystem.getSequence(resourceStream) else null
            }

            if (sequence != null) {
                midiSequencer = MidiSystem.getSequencer()
                midiSequencer?.open()
                midiSequencer?.sequence = sequence
                // Loop continuously while playing
                midiSequencer?.loopCount = Sequencer.LOOP_CONTINUOUSLY
                println("✅ MIDI file loaded successfully!")
            } else {
                println("⚠️ MIDI file not found")
            }
        } catch (e: Exception) {
            println("⚠️ Failed to load MIDI: ${e.message}")
        }
    }

    /**
     * Starts MIDI playback from the beginning.
     * Only plays if MIDI is enabled and the sequencer is available.
     */
    fun startMidi() {
        if (midiEnabled && midiSequencer != null && !midiSequencer!!.isRunning) {
            try {
                midiSequencer?.start()
            } catch (e: Exception) {
                println("⚠️ Failed to start MIDI: ${e.message}")
            }
        }
    }

    /**
     * Stops MIDI playback and resets to the beginning.
     */
    fun stopMidi() {
        if (midiSequencer != null && midiSequencer!!.isRunning) {
            try {
                midiSequencer?.stop()
                midiSequencer?.tickPosition = 0
            } catch (e: Exception) {
                println("⚠️ Failed to stop MIDI: ${e.message}")
            }
        }
    }

    private fun playClip(clip: Clip?) {
        if (soundEnabled && clip != null) {
            if (clip.isRunning) clip.stop()
            clip.framePosition = 0
            clip.start()
        }
    }

    fun playBoom() = playClip(boomClip)
    fun playBing() = playClip(bingClip)
    fun playBlst() = playClip(blstClip)
    fun playXpls() = playClip(xplsClip)
    fun playXpla() = playClip(xplaClip)

    fun playShot(laserLevel: Int) {
        when (laserLevel) {
            0, 1 -> playClip(shotSingleClip)
            2 -> playClip(shotRocketClip)
            3 -> playClip(shotDoubleClip)
            4 -> playClip(shotTripleClip)
            5 -> playClip(shotNrgClip)
        }
    }

    fun playChgLaser(laserLevel: Int) {
        playClip(chgLaserClips.getOrNull(laserLevel))
    }

    fun playSoundOver() = playClip(soundOverClip)
    fun playSoundOn() = playClip(soundOnClip)
}

// ============================================================================
// GAME ENTITIES
// ============================================================================

/**
 * Represents an asteroid in the game.
 * Handles movement, animation frames, and collision detection.
 * Asteroids can be in normal state or exploding state.
 */
class Circle(
    var x: Int, var y: Int, val r: Int, var vx: Int, var vy: Int, val kind: Int, var hit_points: Int
) {
    // Position tracking for smooth movement
    // Note: mx and my represent the CENTER coordinates of the asteroid (x + radius, y + radius)
    var x_old = x; var y_old = y; var mx = x + r; var my = y + r
    var mx_old = mx; var my_old = my

    // Animation frame counter
    var mover = 0

    // State flags
    var expl = false; var expl_ready = false  // expl: currently exploding, expl_ready: explosion animation complete

    // Dimensions (diameter = 2 * radius)
    val width = 2 * r; val height = 2 * r

    // Current animation frame index
    var frame = 0

    // Track if asteroid has been on screen (for edge detection)
    var was_in = false

    /**
     * Updates asteroid position and animation frame.
     * Called every game tick to move the asteroid and advance its animation.
     * Handles both normal movement and explosion animation sequences.
     * Animation speed controlled by ASTEROID_ANIM_TICKS constant.
     */
    fun move() {
        // Store old position for smooth movement
        x_old = x; y_old = y; mx_old = x_old + r; my_old = y_old + r

        // Update position based on velocity
        x += vx; y += vy; mx = x + r; my = y + r

        // Advance animation frame counter
        mover++

        // Determine animation frame range based on state
        val tmp_max = if (expl) 8 else 4      // 8 frames for explosion, 4 for normal
        val tmp_min = if (expl) 4 else 0      // Start at frame 4 when exploding

        // Match original Java behavior: force immediate frame advancement
        // on the first tick of explosion to prevent animation delay
        if (expl) {
            if (frame < tmp_min) {
                frame = tmp_min
            }
            if (frame < tmp_min + 1) {
                mover = ASTEROID_ANIM_TICKS.toInt()
            }
        }

        // Update animation frame every ASTEROID_ANIM_TICKS ticks (controls spinning speed)
        if (mover.toLong() == ASTEROID_ANIM_TICKS) {
            mover = 0
            if (frame + 1 >= tmp_max) {
                if (!expl) frame = tmp_min     // Loop normal animation
                else { expl_ready = true; frame = tmp_max - 1 }  // Stop at last explosion frame
            } else { frame++ }
        }
    }
}

/**
 * Represents the player's shuttle (spaceship).
 * Manages player state including position, shields, lives, ammo, and weapons.
 */
class Shuttle(var x: Int, var y: Int, val width: Int, val height: Int) {
    // Action flags
    var shield = false; var shoot = false; var shoot_zero = false

    // Player stats
    var lives = 5; val max_shield_num = 5; var shield_num = max_shield_num
    var score = 0; val max_ammo = 299; var ammo = max_ammo

    // Animation state
    // frame: normal movement orientation (0-3)
    // frame_ex: explosion animation frame (0-5)
    var frame = 0; var frame_ex = 0; val max_hit_counter = 10; var hit_counter = 0
    var mover = 0 // Used for explosion animation timing

    // Shooting mechanics
    var max_shoot_delay = 10; var shoot_delay = 0

    // Shield mechanics - duration controlled by SHIELD_ANIM_TICKS
    var shield_counter = 0; val max_shield = 90; var hit = false

    // Shield animation - speed controlled by SHIELD_ANIM_TICKS
    var shield_frame = 0; var shield_mover = 0
}

/**
 * Represents a projectile (laser/rocket) fired by the shuttle.
 * Handles movement and animation frames.
 */
class Shot(var x: Int, var y: Int, val width: Int, val height: Int, val vx: Int, val vy: Int, val hit_points: Int, val kind: Int, var frame: Int = 0) {
    var mover = 0

    /**
     * Updates shot position and animation frame.
     * Different shot types have different animation frame counts.
     */
    fun move() {
        x += vx; y += vy; mover++
        if (mover == 4) { mover = 0; frame = (frame + 1) % (if (kind == 2) 3 else 2) }
    }
}

/**
 * Represents a power-up/goodie dropped by destroyed asteroids.
 * Goodies can be weapons, shields, or ammo.
 */
class Goodie(var x: Int, var y: Int, val which: Int, val vx: Int, val vy: Int) {
    val kind = which; var frame = 0; var mover = 0
    val width = 20; val height = 20

    // Color based on goodie type (for fallback graphics)
    val color = when (which) { 0 -> Color.Red; 1 -> Color.Blue; 2 -> Color.Green; else -> Color.Red }

    /**
     * Updates goodie position and animation frame.
     * Goodies fall downward with the background scroll.
     */
    fun move() {
        x += vx; y += vy; mover++
        if (mover == 10) { mover = 0; frame = (frame + 1) % 4 }
    }
}

// ============================================================================
// GAME STATE
// ============================================================================

/**
 * Main game state manager.
 * Contains all game variables, handles game logic, and manages state transitions.
 * Marked as @Stable for Compose optimization.
 */
@Stable
class FasteroidsGameState {
    // Game timing
    var start_game_time by mutableStateOf(0L)
    var end_game_time by mutableStateOf(0L)
    var start_ups by mutableStateOf(-1)

    // UI state
    var story_image_showing by mutableStateOf(0)

    // Network info (unused in standalone version)
    var this_ip by mutableStateOf("0.0.0.0")
    var this_host by mutableStateOf("unknown")

    // First-time initialization flags
    var first_level by mutableStateOf(true)
    var first_lives by mutableStateOf(true)
    var first_shields by mutableStateOf(true)

    // Manual speed control (debug feature)
    var manual_delay_on by mutableStateOf(false)
    var manual_delay by mutableStateOf(10)

    // Player name (for high score)
    var my_name_now by mutableStateOf("")

    // Background rendering flags
    var first_menu_bg by mutableStateOf(true)

    // CPU performance check flag
    var cpu_checker by mutableStateOf(false)

    // Audio settings
    var sound by mutableStateOf(true)
    var midi by mutableStateOf(true)

    // Weapon upgrade tracking
    var laser_counter by mutableStateOf(0)

    // Level progression
    var abs_level by mutableStateOf(1L)
    var fr_start by mutableStateOf(-1)
    var distance by mutableStateOf(0)
    var alt_col by mutableStateOf(0)
    var main_delay by mutableStateOf(14)

    // Life/respawn management
    var next_live by mutableStateOf(false)

    // Fade transition effects
    var fade_to_black by mutableStateOf(false)
    var fade_from_black by mutableStateOf(false)
    var fade_wait by mutableStateOf(false)
    var fade_counter by mutableStateOf(0)
    var fade_wait_counter by mutableStateOf(0)
    var max_fade_counter by mutableStateOf(10)
    var max_fade_wait_counter by mutableStateOf(100)

    // Current weapon state
    var laser by mutableStateOf(0)
    var have_laser by mutableStateOf(0)

    // Game mode flags
    var cheat by mutableStateOf(false)
    var intro by mutableStateOf(true)
    var level by mutableStateOf(1)

    // Game over states
    var over by mutableStateOf(false)
    var almost_over by mutableStateOf(false)

    // Graphics rendering flag
    var gfx_go by mutableStateOf(false)

    // Pause screen state
    var isPaused by mutableStateOf(false)
    var pauseMessage by mutableStateOf("")
    var pauseStartTime by mutableStateOf(0L)

    // NEW: Track whether we're waiting for a key press after the initial delay
    var waitingForKey by mutableStateOf(false)

    // NEW: Track whether shuttle is in the dying animation
    var isDying by mutableStateOf(false)

    // Frame counter for animations
    var frameTick by mutableStateOf(0)

    // Keyboard input state (12 keys: space, S, arrows, 1-6)
    // Index mapping:
    // 0: Spacebar (Shoot)
    // 1: S (Shield)
    // 2: Up Arrow
    // 3: Down Arrow
    // 4: Left Arrow
    // 5: Right Arrow
    // 6-11: Keys 1-6 (Weapon selection)
    val keyDown = BooleanArray(12)

    // Active game entities
    val goodies = mutableListOf<Goodie>()
    val shots = mutableListOf<Shot>()

    // High score data (top 5 players)
    val h_nick = Array(5) { mutableStateOf("Dr. Who ???") }
    val h_score = Array(5) { mutableStateOf(5 - it) }
    val h_rest = Array(5) { mutableStateOf("0|0|0|0|") }

    // Player shuttle instance
    var shuttle_p by mutableStateOf<Shuttle?>(null)

    // Asteroid array and tracking
    var asteroid_p by mutableStateOf<Array<Circle?>>(arrayOfNulls(12))
    var asteroid_last_move by mutableStateOf<LongArray?>(null)
    var ast_now by mutableStateOf(8)

    // Image Assets Manager
    val images = ImageAssets()

    // Sound Assets Manager
    val sounds = SoundManager()

    // Background star field (200 stars with random positions)
    val stars = List(200) {
        Offset((Math.random() * size_x).toFloat(), (Math.random() * size_y).toFloat())
    }

    /**
     * Game constants and configuration
     */
    companion object {
        const val size_x = fasteroids_main_width
        const val size_y = fasteroids_main_height
        const val max_ast = 12
        const val max_ast_kind = 3
        const val shuttle_size_x = 30
        const val shuttle_size_y = 30
        const val max_left = 1
        const val max_right = size_x - shuttle_size_x - 2
        const val max_down = size_y - shuttle_size_y - 2
        const val max_up = 1
        const val level_distance = 4000
        const val scroll_speed = 2
    }

    /**
     * Initializes the game for the intro/title screen.
     * Sets up initial level, creates player shuttle, and spawns starting asteroids.
     */
    fun init_intro() {
        println("init_intro")
        laser = 0; have_laser = 0; fr_start = -1; distance = 0

        // Create player shuttle at bottom center of screen
        shuttle_p = Shuttle((size_x / 2) - shuttle_size_x, max_down, shuttle_size_x, shuttle_size_y).apply { frame = 0 }

        level = 1; abs_level = 1

        // Initialize asteroid array
        asteroid_p = arrayOfNulls(max_ast)
        asteroid_last_move = LongArray(max_ast)
        ast_now = max_ast - 5 + level

        // Show pause screen with level info
        isPaused = true
        waitingForKey = false
        isDying = false
        pauseMessage = "New Game\nLevel $level\nLives: ${shuttle_p?.lives}"
        pauseStartTime = System.currentTimeMillis()

        // Spawn initial asteroids
        repeat(ast_now) { tmp -> asteroid_p[tmp] = create_asteroid(tmp) }
    }

    /**
     * Initializes a new game level.
     * Resets player stats, clears entities, and spawns asteroids for the current level.
     */
    fun init_level() {
        println("init_level")
        distance = 0
        laser = 0; have_laser = 0; laser_counter = 0

        // Clear all active entities
        goodies.clear()
        shots.clear()

        // Reset player shuttle to starting position and stats
        shuttle_p?.let { shuttle ->
            shuttle.x = (size_x / 2) - shuttle_size_x
            shuttle.y = max_down
            shuttle.shield_num = shuttle.max_shield_num
            shuttle.ammo = shuttle.max_ammo
            shuttle.frame_ex = 0; shuttle.frame = 0
            shuttle.hit = false; shuttle.hit_counter = 0
            shuttle.shoot = false; shuttle.shoot_zero = false; shuttle.shoot_delay = 0
            shuttle.shield = true; shuttle.shield_counter = 0
        }

        // Reinitialize asteroid array
        asteroid_p = arrayOfNulls(max_ast)
        asteroid_last_move = LongArray(max_ast)
        ast_now = max_ast - 5 + level

        isPaused = true
        waitingForKey = false
        isDying = false
        pauseMessage = "Level $level\nLives: ${shuttle_p?.lives}"
        pauseStartTime = System.currentTimeMillis()

        repeat(ast_now) { tmp -> asteroid_p[tmp] = create_asteroid(tmp) }
    }

    /**
     * Initializes the next level after completing the current one.
     * Similar to init_level() but with different pause message.
     */
    fun init_next_level() {
        println("init_next_level")
        distance = 0
        goodies.clear()
        shots.clear()

        shuttle_p?.let { shuttle ->
            shuttle.x = (size_x / 2) - shuttle_size_x
            shuttle.y = max_down
            shuttle.shield_num = shuttle.max_shield_num
            shuttle.ammo = shuttle.max_ammo
            shuttle.frame_ex = 0; shuttle.frame = 0
            shuttle.hit = false; shuttle.hit_counter = 0
            shuttle.shoot = false; shuttle.shoot_zero = false; shuttle.shoot_delay = 0
            shuttle.shield = true; shuttle.shield_counter = 0
        }

        asteroid_p = arrayOfNulls(max_ast)
        asteroid_last_move = LongArray(max_ast)
        ast_now = max_ast - 5 + level

        isPaused = true
        waitingForKey = false
        isDying = false
        pauseMessage = "Enter next Level $level\nLives: ${shuttle_p?.lives}"
        pauseStartTime = System.currentTimeMillis()

        repeat(ast_now) { tmp -> asteroid_p[tmp] = create_asteroid(tmp) }
    }

    /**
     * Creates a new asteroid with random properties.
     * Asteroid difficulty scales with level and absolute level.
     *
     * @param num Index of the asteroid in the array
     * @return New Circle (asteroid) instance
     */
    fun create_asteroid(num: Int): Circle {
        val a_kind = (Math.random() * max_ast_kind).toInt()  // Random asteroid type (0-2)
        val rr = 15                                           // Radius
        val hp = if (abs_level in 1..99) (Math.random() * (abs_level + 2) + 1).toInt()
        else if (abs_level >= 100) 999
        else 1                                       // Hit points scale with level

        // Velocity modifiers based on level difficulty
        val level_dx = (Math.random() * (level / 2)).toInt()
        val level_dy = 2 * (Math.random() * (level - 2)).toInt()

        // Random velocity within level-based range
        val vx = (Math.random() * (5 + level_dx) - 2 - level_dx / 2).toInt()
        val vy = (Math.random() * (2 + level_dy) + 2).toInt()

        // Spawn above screen at random horizontal position
        return Circle(x = (-20 + (Math.random() * (size_x + 40))).toInt(), y = -rr - 10, r = rr, vx = vx, vy = vy, kind = a_kind, hit_points = hp)
    }

    /**
     * Creates projectile shots based on current laser level.
     * Different laser levels have different shot patterns and properties.
     *
     * @param laserLevel Current weapon level (0-5)
     * @param shuttle Player shuttle instance
     */
    fun addShotsForLaser(laserLevel: Int, shuttle: Shuttle) {
        when (laserLevel) {
            0 -> shots.add(Shot(shuttle.x + shuttle.width / 2 - 3, shuttle.y, 6, 15, 0, -3, 1, 0))
            1 -> shots.add(Shot(shuttle.x + shuttle.width / 2 - 3, shuttle.y, 6, 15, 0, -4, 1, 0))
            2 -> shots.add(Shot(shuttle.x + shuttle.width / 2 - 3, shuttle.y, 5, 30, 0, -6, 5, 1))
            3 -> {
                // Double shot (left and right)
                shots.add(Shot(shuttle.x + shuttle.width - 7, shuttle.y + 10, 6, 15, 0, -5, 1, 0))
                shots.add(Shot(shuttle.x + 2, shuttle.y + 10, 6, 15, 0, -5, 1, 0))
            }
            4 -> {
                // Triple shot (left, center, right)
                shots.add(Shot(shuttle.x + shuttle.width - 7, shuttle.y + 10, 6, 15, 0, -7, 1, 0))
                shots.add(Shot(shuttle.x + shuttle.width / 2 - 3, shuttle.y, 6, 15, 0, -7, 1, 0))
                shots.add(Shot(shuttle.x + 2, shuttle.y + 10, 6, 15, 0, -7, 1, 0))
            }
            5 -> {
                // Super laser (15 shots in rapid succession)
                shots.clear()
                var fr = -fr_start
                fr_start++
                if (fr_start >= 2) fr_start = -1
                repeat(15) { tmp ->
                    fr++
                    if (fr >= 3) fr = 0
                    shots.add(Shot(shuttle.x + shuttle.width / 2 - 16, shuttle.y - (30 * tmp), 30, 30, 0, -12, 100, 2, fr))
                }
            }
        }
    }

    /**
     * Main game update loop.
     * Called every GAME_TICK_MS milliseconds.
     * Handles all game logic: input, movement, collisions, state transitions.
     */
    fun update() {
        frameTick++

        // ====================================================================
        // PAUSE SCREEN HANDLING
        // ====================================================================
        if (isPaused) {
            // NEW: Check if any key is pressed while waiting
            if (waitingForKey) {
                var anyKeyPressed = keyDown.any { it }
                if (DEMO_SHOWCASE_DEBUG_ONLY)
                {
                    anyKeyPressed = true
                }
                if (anyKeyPressed) {
                    // Resume the game
                    isPaused = false
                    waitingForKey = false

                    // Stop MIDI when resuming gameplay
                    sounds.stopMidi()

                    // Clear all keys to prevent immediate actions
                    for (i in keyDown.indices) {
                        keyDown[i] = false
                    }

                    // Handle the resume logic
                    if (over) {
                        over = false
                        intro = true
                        init_intro()
                    } else if (almost_over) {
                        almost_over = false
                        shuttle_p?.let { shuttle ->
                            shuttle.hit = false
                            shuttle.x = size_x / 2 - shuttle_size_x / 2
                            shuttle.y = max_down
                            shuttle.shield = true
                            shuttle.shield_counter = 0
                            shuttle.frame_ex = 0
                        }
                    }
                }
            } else {
                // Wait for 1 second before showing "press any key" message
                if (System.currentTimeMillis() - pauseStartTime >= 1000) {
                    waitingForKey = true
                    // Start MIDI only when "press any key" text appears
                    // and it's NOT a game over screen
                    if (!over) {
                        sounds.startMidi()
                    }
                }
            }
            return
        }

        val shuttle = shuttle_p ?: return

        // ====================================================================
        // LEVEL PROGRESSION
        // ====================================================================
        distance++
        if (distance >= level_distance) {
            distance = 0
            if (level + 1 > max_level) {
                level = 1
                abs_level++
            } else {
                level++
                abs_level++
            }
            init_next_level()
        }

        // ====================================================================
        // SHIELD MECHANICS
        // ====================================================================
        if (shuttle.shield) {
            // FIXED: Animate shield frame (matching original Java turn_shield() logic)
            // Speed controlled by SHIELD_ANIM_TICKS constant
            // Note: shield_frame alternates between 0 and 1 to create a spinning/rotating visual effect
            shuttle.shield_mover++
            if (shuttle.shield_mover >= SHIELD_ANIM_TICKS) {
                shuttle.shield_mover = 0
                shuttle.shield_frame = (shuttle.shield_frame + 1) % 2
            }

            // Shield duration countdown
            // Shield lasts for (max_shield) game ticks
            shuttle.shield_counter++
            if (shuttle.shield_counter >= shuttle.max_shield) {
                shuttle.shield = false
                shuttle.shield_counter = 0
            }
        }

        // ====================================================================
        // PLAYER MOVEMENT INPUT
        // ====================================================================
        val steer_x = 8; val steer_y = 8

        // Left movement
        if (keyDown[4]) { shuttle.x = (shuttle.x - steer_x).coerceAtLeast(max_left); shuttle.frame = 1 }

        // Right movement
        if (keyDown[5]) { shuttle.x = (shuttle.x + steer_x).coerceAtMost(max_right); shuttle.frame = 2 }

        // Up movement
        if (keyDown[2]) { shuttle.y = (shuttle.y - steer_y).coerceAtLeast(max_up); shuttle.frame = 3 }

        // Down movement
        if (keyDown[3]) { shuttle.y = (shuttle.y + steer_y).coerceAtMost(max_down); shuttle.frame = 0 }

        // Reset to neutral frame when not moving
        if (!keyDown[2] && !keyDown[3] && !keyDown[4] && !keyDown[5]) { shuttle.frame = 0 }

        // ====================================================================
        // SHOOTING INPUT
        // ====================================================================
        if (keyDown[0]) {
            if (!shuttle.shoot && shuttle.shoot_delay == 0) {
                val cost = use_ammo[laser]
                if (shuttle.ammo >= cost) {
                    // Fire weapon if enough ammo
                    shuttle.shoot = true
                    shuttle.shoot_delay = when (laser) { 0 -> 10; 1 -> 8; 2 -> 6; 3 -> 5; 4 -> 4; 5 -> 1; else -> 10 }
                    if (!DEBUG_IT) { shuttle.ammo -= cost }
                    addShotsForLaser(laser, shuttle)
                    sounds.playShot(laser)
                } else if (laser > 2) {
                    // Fire weak shot if out of ammo but have advanced weapon
                    shuttle.shoot_zero = true
                    shuttle.shoot_delay = 10
                    if (!DEBUG_IT) { shuttle.ammo = (shuttle.ammo - use_ammo[0]).coerceAtLeast(0) }
                    shots.add(Shot(shuttle.x + shuttle.width / 2 - 3, shuttle.y, 6, 15, 0, -3, 1, 0))
                    sounds.playShot(0)
                }
            }
        }

        // Shooting cooldown
        if (shuttle.shoot_delay > 0) {
            shuttle.shoot_delay--
            shuttle.shoot = false
            shuttle.shoot_zero = false
        }

        // ====================================================================
        // SHIELD ACTIVATION INPUT
        // ====================================================================
        if (keyDown[1] && !shuttle.shield && shuttle.shield_num > 0) {
            shuttle.shield = true
            shuttle.shield_num--
            shuttle.shield_counter = 0
        }

        // ====================================================================
        // WEAPON SELECTION INPUT (keys 1-6)
        // ====================================================================
        for (i in 6..11) {
            if (keyDown[i]) {
                val targetLaser = i - 6
                if (targetLaser == 0 || have_laser > targetLaser - 1) {
                    if (laser != targetLaser) {
                        laser = targetLaser
                        sounds.playChgLaser(laser)
                    }
                }
            }
        }

        // ====================================================================
        // ASTEROID MOVEMENT AND RESPAWN
        // ====================================================================
        // The game uses a fixed-size array for asteroids to avoid garbage collection pauses.
        // When an asteroid is destroyed or moves off-screen, it is immediately replaced
        // by a new one at the top of the screen via create_asteroid().
        for (tmp in 0 until ast_now) {
            val ast = asteroid_p[tmp] ?: continue

            if (ast.expl_ready) {
                // Asteroid has finished its explosion animation, replace it immediately
                asteroid_p[tmp] = create_asteroid(tmp)
            } else {
                ast.move()
                // If it moved off-screen, replace it
                if (ast.y > size_y || ast.x + ast.width < 0 || ast.x > size_x) {
                    asteroid_p[tmp] = create_asteroid(tmp)
                }
            }
        }

        // ====================================================================
        // SHOT MOVEMENT AND COLLISION WITH ASTEROIDS
        // ====================================================================
        // We use a separate list to track shots that need to be removed.
        // Modifying a collection while iterating over it causes ConcurrentModificationException,
        // so we defer removals until after the loop completes.
        val shotsToRemove = mutableListOf<Shot>()
        for (shot in shots) {
            shot.move()
            if (shot.y + shot.height < 0) { shotsToRemove.add(shot); continue }

            // Check collision with each asteroid
            for (ast in asteroid_p) {
                if (ast != null && !ast.expl) {
                    // AABB collision detection
                    if (shot.x < ast.x + ast.width && shot.x + shot.width > ast.x &&
                        shot.y < ast.y + ast.height && shot.y + shot.height > ast.y) {

                        if (shot.hit_points >= ast.hit_points) {
                            // Destroy asteroid
                            ast.hit_points = 0
                            ast.expl = true
                            shuttle.score += 5
                            sounds.playBoom()

                            // 30% chance to drop a goodie
                            if (Math.random() * 100 > 70) {
                                goodies.add(Goodie(ast.x, ast.y, (Math.random() * 3).toInt(), 0, scroll_speed))
                            }
                        } else {
                            // Damage asteroid (reduce hit points)
                            ast.hit_points -= shot.hit_points
                        }

                        // Remove shot unless it's the super laser (passes through)
                        if (laser != 5) {
                            shotsToRemove.add(shot)
                        }
                        break
                    }
                }
            }
        }
        shots.removeAll(shotsToRemove)

        // ====================================================================
        // GOODIE MOVEMENT AND COLLECTION
        // ====================================================================
        val goodiesToRemove = mutableListOf<Goodie>()
        for (goodie in goodies) {
            goodie.move()
            if (goodie.y > size_y) { goodiesToRemove.add(goodie); continue }

            // Check if shuttle collected the goodie
            if (shuttle.x < goodie.x + goodie.width && shuttle.x + shuttle.width > goodie.x &&
                shuttle.y < goodie.y + goodie.height && shuttle.y + shuttle.height > goodie.y) {

                sounds.playBing()

                when (goodie.which) {
                    0 -> {
                        // Weapon upgrade goodie
                        if (DEBUG_IT) laser_counter += 10
                        laser_counter++
                        if (laser_counter > 100) laser_counter = 100

                        // Upgrade weapon based on counter thresholds.
                        // The logic cascades downwards: if you have enough points for level 5,
                        // it checks level 5 first, then 4, etc.
                        // Note: The original Java logic had some overlapping bounds which are preserved here.
                        if (laser_counter > 50) { if (have_laser < 5) { laser = 5; have_laser = laser } }
                        else if (laser_counter > 20) { if (have_laser < 4) { laser = 4; have_laser = laser } }
                        else if (laser_counter > 12) { if (have_laser < 3) { laser = 3; have_laser = laser } }
                        else if (laser_counter > 5) { if (have_laser < 5) { laser = 2; have_laser = laser } }
                        else if (laser_counter > 3) { if (have_laser < 5) { laser = 1; have_laser = laser } }
                        else { if (laser != 0) { laser = 0; have_laser = laser } }
                    }
                    1 -> {
                        // Shield goodie
                        if (shuttle.shield_num < shuttle.max_shield_num) shuttle.shield_num++
                    }
                    2 -> {
                        // Ammo goodie
                        shuttle.ammo = (shuttle.ammo + 20).coerceAtMost(shuttle.max_ammo)
                    }
                }
                goodiesToRemove.add(goodie)
            }
        }
        goodies.removeAll(goodiesToRemove)

        // ====================================================================
        // SHUTTLE-ASTEROID COLLISION DETECTION
        // ====================================================================
        if (shuttle.hit_counter == 0) {
            for (ast in asteroid_p) {
                if (ast != null && !ast.expl) {
                    var hit = false

                    if (shuttle.shield) {
                        // Circular collision detection for shield (more forgiving)
                        // Calculates distance between the center of the shuttle and the center of the asteroid.
                        // If the distance is less than the sum of their radii, a collision occurred.
                        val shuttleCenterX = shuttle.x + shuttle.width / 2f
                        val shuttleCenterY = shuttle.y + shuttle.height / 2f
                        val shieldRadius = (maxOf(shuttle.width, shuttle.height) / 2f) + 6f
                        val astCenterX = ast.x + ast.r
                        val astCenterY = ast.y + ast.r
                        val dx = astCenterX - shuttleCenterX
                        val dy = astCenterY - shuttleCenterY
                        val distanceSquared = dx * dx + dy * dy
                        val collisionRadius = shieldRadius + ast.r
                        hit = distanceSquared < collisionRadius * collisionRadius
                    } else {
                        // Point-based collision detection for unshielded shuttle
                        // Checks if any of the asteroid's key boundary points intersect with the shuttle's bounding box.
                        // This is less forgiving than circular detection, making the game harder without shields.
                        val astPoints = listOf(
                            Pair(ast.x + ast.r, ast.y + ast.r), Pair(ast.x, ast.y), Pair(ast.x + ast.r, ast.y),
                            Pair(ast.x + ast.width, ast.y), Pair(ast.x, ast.y + ast.height), Pair(ast.x + ast.r, ast.y + ast.height),
                            Pair(ast.x + ast.width, ast.y + ast.height), Pair(ast.x, ast.y + ast.r), Pair(ast.x + ast.width, ast.y + ast.r)
                        )
                        for (pt in astPoints) {
                            if (pt.first > shuttle.x && pt.first < shuttle.x + shuttle.width &&
                                pt.second > shuttle.y && pt.second < shuttle.y + shuttle.height) {
                                hit = true; break
                            }
                        }
                    }

                    if (hit) {
                        @Suppress("SimplifyBooleanWithConstants")
                        if (!shuttle.shield && !DEBUG_IT && !DEMO_SHOWCASE_DEBUG_ONLY) {
                            // Player hit without shield - start death sequence
                            shuttle.hit = true
                            shuttle.hit_counter = shuttle.max_hit_counter
                            shuttle.frame_ex = 0
                            shuttle.shield = false
                            shuttle.shield_counter = 0

                            sounds.playXpls()

                            // NEW: Start dying animation, don't pause immediately
                            isDying = true

                            if (shuttle.lives > 1) {
                                shuttle.lives--
                                almost_over = true
                            } else {
                                shuttle.lives = 0
                                over = true
                                sounds.playSoundOver()
                            }
                        } else {
                            // Shield absorbed the hit - destroy asteroid instead
                            ast.expl = true
                            shuttle.score += 5
                            sounds.playXpla()
                        }
                        break
                    }
                }
            }
        } else {
            // Shuttle is in death animation sequence.
            // The game doesn't pause immediately; it lets the explosion animation play out
            // over several ticks before showing the "Try again" or "GAME OVER" screen.
            shuttle.hit_counter--

            // FIXED: Match original Java expl() logic: advance frame every 5 ticks
            // This slows down the explosion animation to match the original game feel
            shuttle.mover++
            if (shuttle.mover >= 4) { // ast_move_every - 1
                shuttle.mover = 0
                if (shuttle.frame_ex + 1 > 5) {
                    shuttle.frame_ex = 5
                } else {
                    shuttle.frame_ex++
                    shuttle.hit_counter = shuttle.max_hit_counter // reset hit counter to extend animation
                }
            }

            if (shuttle.hit_counter <= 0 && shuttle.frame_ex >= 5) {
                // NEW: Explosion animation complete, now show the waiting screen
                if (isDying) {
                    isDying = false
                    isPaused = true
                    waitingForKey = false
                    pauseStartTime = System.currentTimeMillis()

                    if (almost_over) {
                        pauseMessage = "Try again...\nLives: ${shuttle.lives}"
                    } else if (over) {
                        pauseMessage = "GAME OVER\nFinal Score: ${shuttle.score}"
                    }
                }

                // Reset shuttle after death animation completes
                if (almost_over && !isDying) {
                    almost_over = false
                    shuttle.hit = false
                    shuttle.x = size_x / 2 - shuttle_size_x / 2
                    shuttle.y = max_down
                    shuttle.shield = true
                    shuttle.shield_counter = 0
                    shuttle.frame_ex = 0
                }
                // FIXED: Don't immediately call init_intro() when game is over
                // Let the pause screen handle it when user presses a key
            }
        }
    }
}

// ============================================================================
// COMPOSABLE GAME RENDERER
// ============================================================================

/**
 * Main Compose UI for the game.
 * Renders the game canvas, HUD, and pause screens.
 * Handles keyboard input and window focus.
 */
@Composable
fun FasteroidsGame(state: FasteroidsGameState) {
    val tick = state.frameTick
    var scale by remember { mutableStateOf(INIT_SCALE) }
    val focusRequester = remember { FocusRequester() }

    // Request focus on startup to ensure keyboard input works
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .background(Color(BG_BORDER_COLOR))
            .padding(16.dp)
            .fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .size(((fasteroids_main_width/2)*scale).dp,
                    ((fasteroids_main_height/2)*scale).dp)
                .background(Color.Black)
                .clipToBounds()
                .focusable()
                .focusRequester(focusRequester)
                .clickable { focusRequester.requestFocus() }
                .onPreviewKeyEvent { event ->
                    // Intercept key events before they propagate further.
                    // Maps physical keyboard keys to the game's internal boolean input array (state.keyDown).
                    // This decouples the UI event system from the game logic update loop.
                    val key = event.key
                    val isDown = event.type == KeyEventType.KeyDown

                    // Map keyboard keys to game input array
                    when (key) {
                        Key.Spacebar -> state.keyDown[0] = isDown
                        Key.S -> state.keyDown[1] = isDown
                        Key.DirectionUp -> state.keyDown[2] = isDown
                        Key.DirectionDown -> state.keyDown[3] = isDown
                        Key.DirectionLeft -> state.keyDown[4] = isDown
                        Key.DirectionRight -> state.keyDown[5] = isDown
                        Key.One, Key.NumPad1 -> state.keyDown[6] = isDown
                        Key.Two, Key.NumPad2 -> state.keyDown[7] = isDown
                        Key.Three, Key.NumPad3 -> state.keyDown[8] = isDown
                        Key.Four, Key.NumPad4 -> state.keyDown[9] = isDown
                        Key.Five, Key.NumPad5 -> state.keyDown[10] = isDown
                        Key.Six, Key.NumPad6 -> state.keyDown[11] = isDown
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
        ) {
            // ====================================================================
            // HUD (Heads-Up Display)
            // ====================================================================
            Row(
                modifier = Modifier.fillMaxSize().padding(start = (2 * scale).dp, top = (2 * scale).dp, end = (2 * scale).dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left side: Score and Level
                Column(horizontalAlignment = Alignment.Start) {
                    Text(text = "Score: ${state.shuttle_p?.score ?: 0}", color = score_color, fontSize = (OSD_FONT_SIZE * scale).sp)
                    Text(text = "Level: ${state.level}", color = Color.White, fontSize = (OSD_FONT_SIZE * scale).sp)
                }

                // Right side: Ammo, Shields, Weapons, Lives
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "${state.shuttle_p?.ammo ?: 0} : Ammo", color = ammo_color, fontSize = (OSD_FONT_SIZE * scale).sp)
                    Text(text = "${state.shuttle_p?.shield_num ?: 0} : Shields", color = Color(100, 150, 255), fontSize = (OSD_FONT_SIZE * scale).sp)
                    Text(text = "${state.laser + 1} / ${state.have_laser + 1} : Weapons", color = Color(100, 150, 255), fontSize = (OSD_FONT_SIZE * scale).sp)
                    Text(text = "${state.laser_counter} : Weapon Goodies", color = Color(100, 150, 255), fontSize = (OSD_FONT_SIZE * scale).sp)
                    Text(text = "${state.shuttle_p?.lives ?: 0} : Lives", color = Color(255, 100, 100), fontSize = (OSD_FONT_SIZE * scale).sp)
                }
            }

            // ====================================================================
            // GAME CANVAS
            // ====================================================================
            Canvas(
                modifier = Modifier
                    .size(fasteroids_main_width.dp, fasteroids_main_height.dp)
                    .graphicsLayer {
                        // Apply scaling transformation to the canvas.
                        // TransformOrigin(0f, 0f) ensures scaling happens from the top-left corner.
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                        clip = true }
            ) {
                // Clear background with transparent color (the black background is handled by the parent Box)
                drawRect(Color.Transparent)

                // Draw animated star field
                state.stars.forEach { offset ->
                    val c = (200 + (tick % 55))
                    drawCircle(Color(c, c, c), radius = 1.5f, center = offset)
                }

                // --- ASTEROIDS ---
                state.asteroid_p.forEach { ast ->
                    if (ast != null) {
                        val img = state.images.asteroids.getOrNull(ast.kind)?.getOrNull(ast.frame)
                        if (state.images.isReady && img != null) {
                            drawImage(img, topLeft = Offset(ast.x.toFloat(), ast.y.toFloat()))
                        } else {
                            // FALLBACK GRAPHICS: Used when assets are not yet loaded or missing
                            if (ast.expl) drawCircle(Color.Yellow, ast.r.toFloat(), Offset(ast.x + ast.r.toFloat(), ast.y + ast.r.toFloat()))
                            else drawCircle(Color(170, 64 + (Math.random() * 50).toInt(), 20 + (Math.random() * 30).toInt()), ast.r.toFloat(), Offset(ast.x + ast.r.toFloat(), ast.y + ast.r.toFloat()))
                        }
                    }
                }

                // --- SHUTTLE ---
                val shuttle = state.shuttle_p
                if (shuttle != null) {
                    // Draw shield if active
                    if (shuttle.shield) {
                        // FIXED: Actually draw the shield image if ready, matching Java's rec.x/rec.y offset
                        val shieldImg = state.images.shields.getOrNull(shuttle.shield_frame)
                        if (state.images.isReady && shieldImg != null) {
                            drawImage(shieldImg, topLeft = Offset(shuttle.x - 4f, shuttle.y - 4f))
                        } else {
                            // FALLBACK GRAPHICS: Procedural circle for shield
                            val centerX = shuttle.x + shuttle.width / 2f
                            val centerY = shuttle.y + shuttle.height / 2f
                            val radius = (maxOf(shuttle.width, shuttle.height) / 2f) + 6f
                            drawCircle(
                                color = Color(0, 0, 200),
                                radius = radius,
                                center = Offset(centerX, centerY),
                                style = Stroke(width = 3f)
                            )
                        }
                    }

                    // Draw shuttle or explosion
                    val isExploding = shuttle.hit_counter > 0 || shuttle.frame_ex > 0
                    val shuttleImg = if (isExploding) {
                        state.images.shuttleEx.getOrNull(shuttle.frame_ex.coerceAtLeast(1))
                    } else {
                        state.images.shuttles.getOrNull(shuttle.frame)
                    }

                    if (state.images.isReady && shuttleImg != null) {
                        // FIXED: Apply offset for explosion animation to match original Java position
                        val drawX = if (isExploding) shuttle.x - 22f else shuttle.x.toFloat()
                        val drawY = if (isExploding) shuttle.y - 26f else shuttle.y.toFloat()
                        drawImage(shuttleImg, topLeft = Offset(drawX, drawY))
                    } else {
                        // FALLBACK GRAPHICS: Procedural triangle for shuttle, red box for explosion
                        if (!isExploding) {
                            val points = listOf(
                                Offset(shuttle.x + shuttle.width / 2f, shuttle.y.toFloat()),
                                Offset((shuttle.x + shuttle.width - 3).toFloat(), (shuttle.y + shuttle.height).toFloat()),
                                Offset((shuttle.x + 3).toFloat(), (shuttle.y + shuttle.height).toFloat())
                            )
                            drawLine(Color.White, points[0], points[1], strokeWidth = 2f)
                            drawLine(Color.White, points[1], points[2], strokeWidth = 2f)
                            drawLine(Color.White, points[2], points[0], strokeWidth = 2f)
                            drawRect(Color(170, 40, 105), topLeft = Offset(shuttle.x + shuttle.width / 2f - 2f, shuttle.y + 1f), size = Size(5f, (shuttle.height - 1).toFloat()))
                        } else {
                            drawRect(Color.Red, topLeft = Offset(shuttle.x.toFloat(), shuttle.y.toFloat()), size = Size(shuttle.width.toFloat(), shuttle.height.toFloat()))
                        }
                    }
                }

                // --- SHOTS ---
                state.shots.forEach { shot ->
                    val shotImg = when (shot.kind) {
                        0 -> state.images.lasers0.getOrNull(shot.frame)
                        1 -> state.images.lasers1.getOrNull(shot.frame)
                        2 -> state.images.lasers2.getOrNull(shot.frame)
                        else -> null
                    }

                    if (state.images.isReady && shotImg != null) {
                        drawImage(shotImg, topLeft = Offset(shot.x.toFloat(), shot.y.toFloat()))
                    } else {
                        // FALLBACK GRAPHICS: Colored rectangles for projectiles
                        val color = when (shot.kind) {
                            1 -> Color(255, 100, 50)
                            2 -> Color(255, 255, 100)
                            else -> Color.Red
                        }
                        drawRect(
                            color = color,
                            topLeft = Offset(shot.x.toFloat(), shot.y.toFloat()),
                            size = Size(shot.width.toFloat(), shot.height.toFloat())
                        )
                    }
                }

                // --- GOODIES ---
                state.goodies.forEach { goodie ->
                    val goodieImg = state.images.goodies.getOrNull(goodie.kind)?.getOrNull(goodie.frame)
                    if (state.images.isReady && goodieImg != null) {
                        drawImage(goodieImg, topLeft = Offset(goodie.x.toFloat(), goodie.y.toFloat()))
                    } else {
                        // FALLBACK GRAPHICS: Colored ovals for power-ups
                        drawOval(goodie.color, topLeft = Offset(goodie.x.toFloat(), goodie.y.toFloat()), size = Size(goodie.width.toFloat(), goodie.height.toFloat()))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height((4 * scale).dp))

        // Scale slider for window resizing
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(text = "Scale: ${"%.1f".format(scale)}x",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.width(80.dp))
            Slider(value = scale,
                onValueChange = { scale = it },
                valueRange = 1.0f..4.0f, steps = 5,
                modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height((8 * scale).dp))
    }

    // ====================================================================
    // PAUSE OVERLAY
    // ====================================================================
    if (state.isPaused) {
        val pulse = 1.0f + 0.15f * sin(state.frameTick * 0.15f)
        val alpha = 0.6f + 0.4f * sin(state.frameTick * 0.15f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Main pause message (e.g., "Level 1", "GAME OVER")
                state.pauseMessage.split("\n").forEach { line ->
                    Text(
                        text = line,
                        color = Color.White.copy(alpha = alpha),
                        fontSize = (11 * scale * pulse).sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }

                // NEW: Show "press any key" message after 1 second
                if (state.waitingForKey) {
                    val continueAlpha = 0.5f + 0.3f * sin(state.frameTick * 0.1f)
                    Spacer(modifier = Modifier.height((20 * scale).dp))
                    Text(
                        text = "press any key to continue ...",
                        color = Color.White.copy(alpha = continueAlpha),
                        fontSize = (5 * scale).sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ============================================================================
// INTRO WINDOW
// ============================================================================

/**
 * Second window displayed at startup showing introductory images.
 * Cycles through images on mouse click, resizing to fit while maintaining aspect ratio
 * with a black background. Loops back to main.png at the end.
 */
@Composable
fun IntroWindow(
    resourcesDir: File?,
    onClose: () -> Unit,
    mainWindowX: Dp,
    mainWindowY: Dp,
    mainWindowWidth: Dp
) {
    val introImages = listOf("main.png", "story.png", "weapons.png", "hints.png", "controls.png", "credits.png")
    var currentIndex by remember { mutableStateOf(0) }
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val currentFilename = introImages[currentIndex]

    val introWindowState = rememberWindowState(
        width = intro_window_width.dp,
        height = fasteroids_window_height.dp + ui.dp,
        position = WindowPosition.Absolute(x = mainWindowX + mainWindowWidth + 10.dp, y = mainWindowY)
    )

    // LaunchedEffect triggers whenever the currentFilename or resourcesDir changes.
    // This handles the asynchronous loading of the next image in the sequence when the user clicks.
    LaunchedEffect(currentFilename, resourcesDir) {
        imageBitmap = try {
            val file = File(resourcesDir, "assets/$currentFilename")
            if (file.exists()) {
                val bytes = file.readBytes()
                Image.makeFromEncoded(bytes).toComposeImageBitmap()
            } else {
                val stream = this::class.java.classLoader?.getResourceAsStream("assets/$currentFilename")
                stream?.readAllBytes()?.decodeToImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }

    Window(
        onCloseRequest = onClose,
        title = "Fasteroids - Info",
        state = introWindowState
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable {
                    currentIndex = (currentIndex + 1) % introImages.size
                },
            contentAlignment = Alignment.Center
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap!!,
                    contentDescription = "Intro Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("Loading or not found: $currentFilename", color = Color.White)
            }
        }
    }
}

// ============================================================================
// MAIN ENTRY POINT
// ============================================================================

/**
 * Application entry point.
 * Initializes game state, starts game loop, and creates the main window.
 */
fun main(args: Array<String>) = application(exitProcessOnExit = true) {
    // HINT: set directory to load files
    //       it corresponds to "resources/common/" in the source tree
    resourcesDir = File(System.getProperty("compose.application.resources.dir"))

    val showIntroWindow = remember { mutableStateOf(true) }

    val mainWindowState = rememberWindowState(
        width = fasteroids_window_width.dp,
        height = fasteroids_window_height.dp + ui.dp,
        position = WindowPosition.Absolute(x = 100.dp, y = 100.dp)
    )

    if (showIntroWindow.value) {
        IntroWindow(
            resourcesDir = resourcesDir,
            onClose = { showIntroWindow.value = false },
            mainWindowX = 100.dp,
            mainWindowY = 100.dp,
            mainWindowWidth = fasteroids_window_width.dp
        )
    }

    val state = remember { FasteroidsGameState() }

    LaunchedEffect(Unit) {
        state.init_intro()

        // DELIBERATE 3-SECOND DELAY before lazy-loading images and sounds
        // This ensures you see the pure fallback graphics first, then they seamlessly switch.
        // This is useful for debugging the procedural rendering logic without needing asset files.
        launch {
            state.sounds.load()
            delay(3000.milliseconds)
            state.images.load()
        }

        var lastTime = System.currentTimeMillis()

        // Main game loop: runs indefinitely on a background coroutine.
        // Uses a fixed time step (GAME_TICK_MS) to ensure consistent game speed
        // regardless of the monitor's refresh rate or frame rendering time.
        while (true) {
            val currentTime = System.currentTimeMillis()
            val elapsedTime = currentTime - lastTime

            if (elapsedTime >= GAME_TICK_MS) {
                try {
                    state.update()
                    lastTime = currentTime
                } catch (e: Exception) {
                    println("Game loop error: ${e.message}")
                    e.printStackTrace()
                }
            } else {
                delay((GAME_TICK_MS - elapsedTime).milliseconds)
            }
        }
    }

    // Create main game window
    Window(
        onCloseRequest = ::exitApplication,
        title = "Fasteroids - Desktop",
        state = mainWindowState
    ) {
        MaterialTheme { FasteroidsGame(state) }
    }
}

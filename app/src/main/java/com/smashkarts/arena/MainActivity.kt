package com.smashkarts.arena

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.opengl.Matrix
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import kotlin.math.*
import kotlin.random.Random

/**
 * Ultra-Realistic Native 120 FPS Android PBR Kart Battle Arena powered by Google Filament.
 * Includes pre-bundled PBR .glb assets (wet asphalt + normal/MR maps, carbon fiber karts, golden-hour sky dome),
 * AI bots, mystery weapon crates, rockets/nukes/boosts, drift physics, and procedural AudioTrack SFX.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        init { Utils.init() }
    }

    private data class WallBox(val minX: Float, val maxX: Float, val minZ: Float, val maxZ: Float)

    private data class Kart(
        val name: String,
        val isPlayer: Boolean,
        val asset: FilamentAsset,
        var x: Float,
        var y: Float = 0f,
        var z: Float,
        var vy: Float = 0f,
        var angle: Float = 0f,
        var speed: Float = 0f,
        var hp: Float = 100f,
        var kills: Int = 0,
        var alive: Boolean = true,
        var respawnTimer: Float = 0f,
        var boostTimer: Float = 0f,
        var weapon: String = "Rockets",
        var ammo: Int = 2,
        var fireCooldown: Float = 0f,
        var aiTimer: Float = 0f,
        var aiBias: Float = 0f
    )

    private data class LootBox(
        val asset: FilamentAsset,
        val x: Float,
        val z: Float,
        var active: Boolean = true,
        var respawnTimer: Float = 0f
    )

    private data class Projectile(
        val owner: Kart,
        val asset: FilamentAsset,
        var x: Float,
        var y: Float,
        var z: Float,
        val vx: Float,
        val vz: Float,
        var life: Float,
        val damage: Float,
        val radius: Float,
        val isNuke: Boolean
    )

    private lateinit var surfaceView: SurfaceView
    private lateinit var uiHelper: UiHelper
    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private var swapChain: SwapChain? = null

    private lateinit var assetLoader: AssetLoader
    private lateinit var resourceLoader: ResourceLoader
    private val sfx = SoundEngine()

    private val loadedAssets = mutableListOf<FilamentAsset>()
    private var sunLightEntity: Int = 0
    private var fillLightEntity: Int = 0

    private val karts = mutableListOf<Kart>()
    private var playerKart: Kart? = null
    private val lootBoxes = mutableListOf<LootBox>()
    private val projectiles = mutableListOf<Projectile>()

    private val walls = listOf(
        WallBox(-86.75f, 86.75f, -86.75f, -83.25f),
        WallBox(-86.75f, 86.75f, 83.25f, 86.75f),
        WallBox(-86.75f, -83.25f, -86.75f, 86.75f),
        WallBox(83.25f, 86.75f, -86.75f, 86.75f),
        WallBox(-30f, -22f, -30f, -22f),
        WallBox(22f, 30f, -30f, -22f),
        WallBox(-30f, -22f, 22f, 30f),
        WallBox(22f, 30f, 22f, 30f),
        WallBox(-8f, 8f, -59.75f, -56.25f),
        WallBox(-8f, 8f, 56.25f, 59.75f),
        WallBox(-59.75f, -56.25f, -8f, 8f),
        WallBox(56.25f, 59.75f, -8f, 8f)
    )

    private val boostPads = listOf(
        Pair(0f, -42f), Pair(0f, 42f), Pair(-42f, 0f), Pair(42f, 0f)
    )

    private val choreographer = Choreographer.getInstance()
    private var lastFrameNanos = 0L
    private var matchTimer = 180f
    private var joyX = 0f
    private var joyY = 0f
    private var drifting = false
    private var hudAccum = 0f
    private var fpsFrames = 0
    private var fpsAccum = 0f
    private var currentFps = 120

    private lateinit var hudTop: TextView
    private lateinit var hudStats: TextView
    private lateinit var fireBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        setupHudOverlay(root)
        setContentView(root)
        setupFilament()
    }

    private fun setupFilament() {
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera(engine.entityManager.create())

        view.scene = scene
        view.camera = camera

        // Unreal-style Post-Processing: ACES Filmic Tone Mapping + Bloom + SSAO + FXAA
        view.colorGrading = ColorGrading.Builder()
            .toneMapping(ColorGrading.ToneMapping.ACES)
            .build(engine)
        view.bloomOptions = View.BloomOptions().apply {
            enabled = true
            strength = 0.22f
        }
        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply {
            enabled = true
        }
        view.antiAliasing = View.AntiAliasing.FXAA

        // Golden-Hour Directional Sun Light with Soft Shadows + Cool Sky Fill Light
        sunLightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.90f, 0.74f)
            .intensity(125_000.0f)
            .direction(-0.45f, -0.78f, -0.42f)
            .castShadows(true)
            .build(engine, sunLightEntity)
        scene.addEntity(sunLightEntity)

        fillLightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.45f, 0.68f, 1.0f)
            .intensity(32_000.0f)
            .direction(0.5f, -0.4f, 0.6f)
            .castShadows(false)
            .build(engine, fillLightEntity)
        scene.addEntity(fillLightEntity)

        val matProvider: MaterialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, matProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        // Load Arena + Spawn Player & 5 AI Bot Karts + 8 Mystery Loot Boxes
        loadGlb("models/arena.glb")

        val spawnCoords = listOf(
            Pair(0f, 0f), Pair(-40f, -40f), Pair(40f, -40f),
            Pair(-40f, 40f), Pair(40f, 40f), Pair(0f, -68f)
        )
        val botNames = listOf("You", "TurboFox", "NitroRex", "DriftKing", "Blaze", "Shadow")
        spawnCoords.forEachIndexed { idx, (sx, sz) ->
            loadGlb("models/kart.glb")?.let { asset ->
                val k = Kart(
                    name = botNames[idx],
                    isPlayer = (idx == 0),
                    asset = asset,
                    x = sx,
                    z = sz
                )
                karts.add(k)
                if (idx == 0) playerKart = k
            }
        }

        val boxCoords = listOf(
            Pair(-16f, 0f), Pair(16f, 0f), Pair(0f, -16f), Pair(0f, 16f),
            Pair(-48f, -48f), Pair(48f, -48f), Pair(-48f, 48f), Pair(48f, 48f)
        )
        boxCoords.forEach { (bx, bz) ->
            loadGlb("models/lootbox.glb")?.let { asset ->
                lootBoxes.add(LootBox(asset, bx, bz))
            }
        }

        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: android.view.Surface) {
                    swapChain?.let { engine.destroySwapChain(it) }
                    swapChain = engine.createSwapChain(surface)
                }
                override fun onDetachedFromSurface() {
                    swapChain?.let { engine.destroySwapChain(it); swapChain = null }
                }
                override fun onResized(width: Int, height: Int) {
                    view.viewport = Viewport(0, 0, width, height)
                    camera.setProjection(60.0, width.toDouble() / height.toDouble(), 0.2, 600.0, Camera.Fov.VERTICAL)
                }
            }
            attachTo(surfaceView)
        }
    }

    private fun loadGlb(assetPath: String): FilamentAsset? {
        return try {
            val bytes = assets.open(assetPath).readBytes()
            val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
            val asset = assetLoader.createAsset(buffer) ?: return null
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            scene.addEntities(asset.entities)
            loadedAssets.add(asset)
            asset
        } catch (_: Exception) {
            null
        }
    }

    private fun setTransform(entity: Int, x: Float, y: Float, z: Float, rotYRad: Float, scale: Float = 1f) {
        val tm = engine.transformManager
        val inst = tm.getInstance(entity)
        if (inst != 0) {
            val m = FloatArray(16)
            Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, x, y, z)
            Matrix.rotateM(m, 0, Math.toDegrees(rotYRad.toDouble()).toFloat(), 0f, 1f, 0f)
            Matrix.scaleM(m, 0, scale, scale, scale)
            tm.setTransform(inst, m)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHudOverlay(root: FrameLayout) {
        hudTop = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(28, 18, 28, 18)
            background = GradientDrawable().apply {
                setColor(0xAA0F172A.toInt())
                cornerRadius = 28f
            }
            text = "SMASH KARTS PBR • 3:00 • 120 FPS"
        }
        root.addView(hudTop, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { setMargins(0, 24, 0, 0) })

        hudStats = TextView(this).apply {
            setTextColor(0xFFFACC15.toInt())
            textSize = 14f
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                setColor(0xAA0F172A.toInt())
                cornerRadius = 24f
            }
            text = "HP: 100 | KILLS: 0"
        }
        root.addView(hudStats, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply { setMargins(24, 24, 0, 0) })

        // Left Virtual Analog Joystick
        val joyPad = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x551E293B)
                setStroke(4, 0x88FFFFFF.toInt())
            }
            setOnTouchListener { v, event ->
                val cx = v.width / 2f
                val cy = v.height / 2f
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        val dx = (event.x - cx) / cx
                        val dy = (event.y - cy) / cy
                        val len = hypot(dx, dy).coerceAtLeast(1f)
                        joyX = dx / len
                        joyY = dy / len
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        joyX = 0f; joyY = 0f
                    }
                }
                true
            }
        }
        root.addView(joyPad, FrameLayout.LayoutParams(250, 250, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(36, 0, 0, 36)
        })

        // Drift / Hop Button
        val driftBtn = Button(this).apply {
            text = "DRIFT"
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF59E0B.toInt())
                setStroke(5, Color.WHITE)
            }
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    drifting = true
                    playerKart?.let { if (it.y <= 0.1f) it.vy = 11f }
                } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    drifting = false
                    playerKart?.let {
                        it.boostTimer = max(it.boostTimer, 0.9f)
                        sfx.playBoost()
                    }
                }
                true
            }
        }
        root.addView(driftBtn, FrameLayout.LayoutParams(155, 155, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, 275, 42)
        })

        // Iconic Big Blue Weapon Fire Circle
        fireBtn = Button(this).apply {
            text = "🚀 x2"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0284C7.toInt())
                setStroke(8, Color.WHITE)
            }
            setOnClickListener { playerKart?.let { fireWeapon(it) } }
        }
        root.addView(fireBtn, FrameLayout.LayoutParams(215, 215, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, 36, 36)
        })
    }

    private fun fireWeapon(k: Kart) {
        if (!k.alive || k.ammo <= 0 || k.fireCooldown > 0f) return
        val asset = loadGlb("models/rocket.glb") ?: return
        val isNuke = (k.weapon == "Nuke")
        val speed = if (isNuke) 48f else 62f
        val sx = k.x + sin(k.angle) * 2.8f
        val sz = k.z + cos(k.angle) * 2.8f
        projectiles.add(
            Projectile(
                owner = k,
                asset = asset,
                x = sx, y = 1.1f, z = sz,
                vx = sin(k.angle) * speed,
                vz = cos(k.angle) * speed,
                life = 2.4f,
                damage = if (isNuke) 110f else 65f,
                radius = if (isNuke) 15f else 6.5f,
                isNuke = isNuke
            )
        )
        k.ammo--
        k.fireCooldown = 0.35f
        if (k.ammo <= 0) k.weapon = "Empty"
        if (k.isPlayer) {
            sfx.playRocket()
            updateFireButton()
        }
    }

    private fun updateFireButton() {
        val p = playerKart ?: return
        fireBtn.text = if (p.ammo > 0) "${if (p.weapon == "Nuke") "☢️" else "🚀"} x${p.ammo}" else "EMPTY"
    }

    private fun resolveWalls(k: Kart) {
        val r = 1.65f
        for (w in walls) {
            val cx = k.x.coerceIn(w.minX, w.maxX)
            val cz = k.z.coerceIn(w.minZ, w.maxZ)
            val dx = k.x - cx
            val dz = k.z - cz
            val distSq = dx * dx + dz * dz
            if (distSq < r * r) {
                val dist = sqrt(distSq).coerceAtLeast(0.001f)
                val overlap = r - dist
                k.x += (dx / dist) * overlap
                k.z += (dz / dist) * overlap
                k.speed *= 0.65f
            }
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            val dt = if (lastFrameNanos == 0L) 0.016f else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNanos = frameTimeNanos

            fpsFrames++
            fpsAccum += dt
            if (fpsAccum >= 0.5f) {
                currentFps = (fpsFrames / fpsAccum).roundToInt()
                fpsFrames = 0; fpsAccum = 0f
            }

            matchTimer = (matchTimer - dt).coerceAtLeast(0f)

            // Update Karts
            for (k in karts) {
                if (!k.alive) {
                    k.respawnTimer -= dt
                    setTransform(k.asset.root, k.x, -20f, k.z, k.angle, 0.01f)
                    if (k.respawnTimer <= 0f) {
                        k.alive = true; k.hp = 100f; k.speed = 0f
                        k.x = Random.nextFloat() * 90f - 45f
                        k.z = Random.nextFloat() * 90f - 45f
                    }
                    continue
                }

                if (k.fireCooldown > 0f) k.fireCooldown -= dt
                if (k.boostTimer > 0f) k.boostTimer -= dt

                if (k.isPlayer) {
                    val accel = -joyY
                    val steer = -joyX
                    val maxSpd = if (k.boostTimer > 0f) 44f else 32f
                    k.speed = if (abs(accel) > 0.05f) (k.speed + accel * 52f * dt).coerceIn(-16f, maxSpd) else k.speed * 0.94f
                    val turnMult = if (drifting) 1.35f else 1.0f
                    k.angle += steer * 2.9f * turnMult * dt
                } else {
                    k.aiTimer -= dt
                    if (k.aiTimer <= 0f) {
                        k.aiTimer = 0.7f + Random.nextFloat() * 0.8f
                        k.aiBias = (Random.nextFloat() - 0.5f) * 0.45f
                    }
                    val target = karts.firstOrNull { it != k && it.alive }
                    if (target != null) {
                        val desired = atan2(target.x - k.x, target.z - k.z) + k.aiBias
                        var diff = desired - k.angle
                        while (diff > PI) diff -= (2 * PI).toFloat()
                        while (diff < -PI) diff += (2 * PI).toFloat()
                        k.angle += diff.coerceIn(-1f, 1f) * 2.5f * dt
                        k.speed += (26f - k.speed) * (dt * 3f).coerceAtMost(1f)
                        if (abs(diff) < 0.3f && hypot(target.x - k.x, target.z - k.z) < 48f && k.ammo > 0) {
                            fireWeapon(k)
                        }
                    }
                }

                k.x += sin(k.angle) * k.speed * dt
                k.z += cos(k.angle) * k.speed * dt
                if (k.y > 0f || k.vy != 0f) {
                    k.y += k.vy * dt
                    k.vy -= 30f * dt
                    if (k.y <= 0f) { k.y = 0f; k.vy = 0f }
                }

                resolveWalls(k)

                for ((bx, bz) in boostPads) {
                    if (hypot(k.x - bx, k.z - bz) < 4.5f) {
                        if (k.isPlayer && k.boostTimer <= 0.1f) sfx.playBoost()
                        k.boostTimer = 1.3f
                        k.speed = max(k.speed, 40f)
                    }
                }

                setTransform(k.asset.root, k.x, k.y, k.z, k.angle, 1f)
            }

            // Update Loot Boxes
            val timeSec = frameTimeNanos / 1_000_000_000f
            for (b in lootBoxes) {
                if (!b.active) {
                    b.respawnTimer -= dt
                    setTransform(b.asset.root, b.x, -20f, b.z, 0f, 0.01f)
                    if (b.respawnTimer <= 0f) b.active = true
                    continue
                }
                setTransform(b.asset.root, b.x, 0.3f * sin(timeSec * 3f + b.x), b.z, timeSec * 1.8f, 1f)
                for (k in karts) {
                    if (k.alive && hypot(k.x - b.x, k.z - b.z) < 2.8f) {
                        b.active = false
                        b.respawnTimer = 6f
                        if (k.ammo <= 0) {
                            val isNuke = Random.nextFloat() < 0.25f
                            k.weapon = if (isNuke) "Nuke" else "Rockets"
                            k.ammo = if (isNuke) 1 else 2
                            if (k.isPlayer) { sfx.playPickup(); updateFireButton() }
                        }
                        break
                    }
                }
            }

            // Update Projectiles
            val iter = projectiles.iterator()
            while (iter.hasNext()) {
                val p = iter.next()
                p.life -= dt
                p.x += p.vx * dt
                p.z += p.vz * dt
                setTransform(p.asset.root, p.x, p.y, p.z, atan2(p.vx, p.vz), if (p.isNuke) 1.7f else 1f)

                var hit = p.life <= 0f || abs(p.x) > 84f || abs(p.z) > 84f
                if (!hit) {
                    for (k in karts) {
                        if (k.alive && k != p.owner && hypot(k.x - p.x, k.z - p.z) < 2.5f) {
                            hit = true; break
                        }
                    }
                }
                if (hit) {
                    sfx.playExplosion(p.isNuke)
                    for (k in karts) {
                        if (k.alive && k != p.owner && hypot(k.x - p.x, k.z - p.z) <= p.radius) {
                            k.hp -= p.damage
                            if (k.hp <= 0f) {
                                k.alive = false
                                k.respawnTimer = 3f
                                p.owner.kills++
                            }
                        }
                    }
                    setTransform(p.asset.root, 0f, -50f, 0f, 0f, 0.001f)
                    iter.remove()
                }
            }

            // Chase Camera Follow Player
            playerKart?.let { p ->
                val camX = p.x - sin(p.angle) * 13.5
                val camZ = p.z - cos(p.angle) * 13.5
                camera.lookAt(camX, 6.2 + p.y, camZ, p.x.toDouble(), 1.5 + p.y, p.z.toDouble(), 0.0, 1.0, 0.0)

                hudAccum += dt
                if (hudAccum >= 0.2f) {
                    hudAccum = 0f
                    val mins = (matchTimer / 60).toInt()
                    val secs = (matchTimer % 60).toInt()
                    hudTop.text = "SMASH KARTS PBR • %d:%02d • %d FPS".format(mins, secs, currentFps)
                    hudStats.text = "HP: ${p.hp.toInt().coerceAtLeast(0)} | KILLS: ${p.kills} | ${p.weapon}"
                }
            }

            swapChain?.let { sc ->
                if (renderer.beginFrame(sc, frameTimeNanos)) {
                    renderer.render(view)
                    renderer.endFrame()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(frameCallback)
        uiHelper.detach()
        loadedAssets.forEach { assetLoader.destroyAsset(it) }
        assetLoader.destroy()
        resourceLoader.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        engine.destroy()
    }
}

package com.nothing.glyphmatrix.games.jump

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.util.Log
import com.nothing.glyphmatrix.games.GlyphMatrixService
import com.nothing.ketchum.GlyphMatrixManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

// ========================= 基礎資料結構 =========================
data class Vector2D(var x: Float, var y: Float) {
    operator fun plus(other: Vector2D) = Vector2D(x + other.x, y + other.y)
    operator fun minus(other: Vector2D) = Vector2D(x - other.x, y - other.y)
    operator fun times(scalar: Float) = Vector2D(x * scalar, y * scalar)
}

data class CollisionBox(
    var position: Vector2D,
    var size: Vector2D
) {
    fun intersects(other: CollisionBox): Boolean {
        return position.x < other.position.x + other.size.x &&
                position.x + size.x > other.position.x &&
                position.y < other.position.y + other.size.y &&
                position.y + size.y > other.position.y
    }
}

// ========================= 策略模式：平台類型 =========================
interface IPlatformStrategy {
    fun onPlayerCollision(player: Player)
    fun update(platform: Platform)
    fun getColor(): Int
}

class NormalPlatformStrategy : IPlatformStrategy {
    override fun onPlayerCollision(player: Player) {
        player.jump()
    }
    
    override fun update(platform: Platform) {
        // 普通平台不需要特殊更新邏輯
    }
    
    override fun getColor(): Int = 255 // 白色
}

// 未來可擴充的平台類型
class BouncyPlatformStrategy : IPlatformStrategy {
    override fun onPlayerCollision(player: Player) {
        player.superJump() // 超級跳躍
    }
    
    override fun update(platform: Platform) {}
    override fun getColor(): Int = 100 // 較暗的白色
}

class MovingPlatformStrategy : IPlatformStrategy {
    private var direction = 1f
    private val speed = 0.5f
    
    override fun onPlayerCollision(player: Player) {
        player.jump()
    }
    
    override fun update(platform: Platform) {
        platform.position.x += direction * speed
        if (platform.position.x <= 0 || platform.position.x >= Game.SCREEN_WIDTH - platform.size.x) {
            direction *= -1
        }
    }
    
    override fun getColor(): Int = 180
}


// ========================= 觀察者模式：計分系統 =========================
interface IScoreObserver {
    fun onScoreChanged(newScore: Int, highScore: Int)
}

class ScoreManager {
    private val observers = mutableListOf<IScoreObserver>()
    private var currentScore = 0
    private var highScore = 0
    
    fun addObserver(observer: IScoreObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: IScoreObserver) {
        observers.remove(observer)
    }
    
    fun updateScore(playerY: Float) {
        val newScore = maxOf(0, (Game.SCREEN_HEIGHT - playerY).toInt() * 10)
        if (newScore > currentScore) {
            currentScore = newScore
            if (currentScore > highScore) {
                highScore = currentScore
            }
            notifyObservers()
        }
    }
    
    fun resetScore() {
        currentScore = 0
        notifyObservers()
    }
    
    fun getCurrentScore() = currentScore
    fun getHighScore() = highScore
    
    private fun notifyObservers() {
        observers.forEach { it.onScoreChanged(currentScore, highScore) }
    }
}

// ========================= 工廠模式：遊戲物件創建 =========================
interface IGameObjectFactory {
    fun createPlayer(position: Vector2D): Player
    fun createPlatform(position: Vector2D, strategy: IPlatformStrategy): Platform
}

class GameObjectFactory : IGameObjectFactory {
    override fun createPlayer(position: Vector2D): Player {
        return Player(position)
    }
    
    override fun createPlatform(position: Vector2D, strategy: IPlatformStrategy): Platform {
        return Platform(position, strategy)
    }
}

// ========================= 遊戲物件類別 =========================
open class GameObject(
    var position: Vector2D,
    var size: Vector2D,
    var velocity: Vector2D = Vector2D(0f, 0f)
) {
    val collisionBox: CollisionBox
        get() = CollisionBox(position, size)
    
    open fun update() {
        position = position + velocity
        
        // 邊界檢查 - 水平包裹
        if (position.x < 0) {
            position.x = Game.SCREEN_WIDTH - size.x
        } else if (position.x + size.x > Game.SCREEN_WIDTH) {
            position.x = 0f
        }
    }
}

class Player(
    position: Vector2D,
    size: Vector2D = Vector2D(2f, 2f)
) : GameObject(position, size) {
    
    companion object {
        private const val JUMP_FORCE = -2f
        private const val SUPER_JUMP_FORCE = -6f
        private const val GRAVITY = 0.15f
        private const val MAX_HORIZONTAL_SPEED = 2f
    }
    
    private var isOnPlatform = false
    var maxHeightReached = position.y
        private set
    
    fun jump() {
        if (velocity.y >= 0) { // 只有在向下墜落或靜止時才能跳躍
            velocity.y = JUMP_FORCE
        }
    }
    
    fun superJump() {
        velocity.y = SUPER_JUMP_FORCE
    }
    
    fun setHorizontalVelocity(vx: Float) {
        velocity.x = max(-MAX_HORIZONTAL_SPEED, min(MAX_HORIZONTAL_SPEED, vx))
    }
    
    override fun update() {
        // 應用重力
        velocity.y += GRAVITY
        
        // 更新最高到達高度
        if (position.y < maxHeightReached) {
            maxHeightReached = position.y
        }
        
        super.update()
    }
    
    fun reset(newPosition: Vector2D) {
        position = newPosition
        velocity = Vector2D(0f, 0f)
        maxHeightReached = newPosition.y
    }
}

class Platform(
    position: Vector2D,
    private val strategy: IPlatformStrategy,
    size: Vector2D = Vector2D(4f, 1f)
) : GameObject(position, size) {
    
    fun onPlayerCollision(player: Player) {
        strategy.onPlayerCollision(player)
    }
    
    override fun update() {
        strategy.update(this)
        super.update()
    }
    
    fun getColor(): Int = strategy.getColor()
}

// ========================= 渲染系統 =========================
class GameRenderer(private val glyphMatrixManager: GlyphMatrixManager?) {
    
    fun renderGame(player: Player, platforms: List<Platform>) {
        val grid = IntArray(Game.SCREEN_WIDTH * Game.SCREEN_HEIGHT) { 0 }
        
        // 渲染平台
        platforms.forEach { platform ->
            drawRectangle(grid, platform.position, platform.size, platform.getColor())
        }
        
        // 渲染玩家
        drawRectangle(grid, player.position, player.size, 255)
        
        glyphMatrixManager?.setMatrixFrame(grid)
    }
    
    fun renderHomeScreen() {
        Log.d("GameRenderer", "Rendering Home Screen")

        val grid = IntArray(Game.SCREEN_WIDTH * Game.SCREEN_HEIGHT) { 0 }
        
        // 繪製簡單的開始畫面 - 中央顯示玩家圖示
        val centerX = Game.SCREEN_WIDTH / 2 - 1
        val centerY = Game.SCREEN_HEIGHT / 2 - 1
        drawRectangle(grid, Vector2D(centerX.toFloat(), centerY.toFloat()), Vector2D(2f, 2f), 255)
        
        // 繪製幾個示範平台
        drawRectangle(grid, Vector2D(5f, 15f), Vector2D(4f, 1f), 200)
        drawRectangle(grid, Vector2D(15f, 10f), Vector2D(4f, 1f), 200)
        drawRectangle(grid, Vector2D(8f, 5f), Vector2D(4f, 1f), 200)
        
        glyphMatrixManager?.setMatrixFrame(grid)
    }
    
    fun renderGameOverScreen(score: Int, highScore: Int) {
        val grid = IntArray(Game.SCREEN_WIDTH * Game.SCREEN_HEIGHT) { 0 }
        
        // 繪製重新開始圖示（簡單的方框）
        val centerX = Game.SCREEN_WIDTH / 2 - 2
        val centerY = Game.SCREEN_HEIGHT / 2 - 2
        
        // 繪製邊框
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                if (i == 0 || i == 3 || j == 0 || j == 3) {
                    val index = (centerY + i) * Game.SCREEN_WIDTH + (centerX + j)
                    if (index in grid.indices) {
                        grid[index] = 255
                    }
                }
            }
        }
        
        glyphMatrixManager?.setMatrixFrame(grid)
    }
    
    private fun drawRectangle(grid: IntArray, position: Vector2D, size: Vector2D, color: Int) {
        val startX = position.x.toInt()
        val startY = position.y.toInt()
        val width = size.x.toInt()
        val height = size.y.toInt()
        
        for (y in startY until (startY + height)) {
            for (x in startX until (startX + width)) {
                if (x in 0 until Game.SCREEN_WIDTH && y in 0 until Game.SCREEN_HEIGHT) {
                    val index = y * Game.SCREEN_WIDTH + x
                    if (index in grid.indices) {
                        grid[index] = color
                    }
                }
            }
        }
    }
}

// ========================= 主要遊戲類別 =========================
class Game : IScoreObserver {
    companion object {
        const val SCREEN_WIDTH = 25
        const val SCREEN_HEIGHT = 25
        private const val PLATFORM_COUNT = 8
        private const val CAMERA_FOLLOW_THRESHOLD = 8f
    }
    
    enum class GameState {
        HOME,
        PLAYING,
        PAUSED,
        GAME_OVER
    }
    
    var gameState = GameState.HOME
        private set
    
    private val factory = GameObjectFactory()
    private val scoreManager = ScoreManager()
    private var renderer: GameRenderer? = null
    
    private var player: Player = factory.createPlayer(Vector2D(12f, 20f))
    private val platforms = mutableListOf<Platform>()
    
    private var gameLoop: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    
    private var cameraOffset = 0f
    
    init {
        scoreManager.addObserver(this)
        initializePlatforms()
    }
    
    fun setRenderer(renderer: GameRenderer) {
        this.renderer = renderer
    }
    
    private fun initializePlatforms() {
        platforms.clear()
        
        // 創建起始平台
        platforms.add(factory.createPlatform(Vector2D(10f, 22f), NormalPlatformStrategy()))
        
        // 創建其他平台
        var currentY = 18f
        repeat(PLATFORM_COUNT - 1) {
            val x = Random.nextFloat() * (SCREEN_WIDTH - 4)
            val strategy = when (Random.nextInt(100)) {
                in 0..79 -> NormalPlatformStrategy() // 80% 普通平台
                in 80..94 -> BouncyPlatformStrategy() // 15% 彈性平台
                else -> MovingPlatformStrategy() // 5% 移動平台
            }
            platforms.add(factory.createPlatform(Vector2D(x, currentY), strategy))
            currentY -= Random.nextFloat() * 4 + 2 // 平台間距 2-6
        }
    }
    
    fun startGame() {
        if (gameState != GameState.HOME && gameState != GameState.GAME_OVER) return
        
        gameState = GameState.PLAYING
        
        if (gameState == GameState.GAME_OVER) {
            restartGame()
        }
        
        scoreManager.resetScore()
        
        gameLoop = coroutineScope.launch {
            while (isActive && gameState == GameState.PLAYING) {
                update()
                render()
                delay(50) // 20 FPS
            }
        }
    }
    
    fun pauseGame() {
        if (gameState == GameState.PLAYING) {
            gameState = GameState.PAUSED
            gameLoop?.cancel()
        }
    }
    
    fun resumeGame() {
        if (gameState == GameState.PAUSED) {
            startGame()
        }
    }
    
    fun restartGame() {
        gameLoop?.cancel()
        player.reset(Vector2D(12f, 20f))
        cameraOffset = 0f
        initializePlatforms()
        scoreManager.resetScore()
        gameState = GameState.HOME
    }
    
    fun updatePlayerMovement(tiltX: Float) {
        if (gameState == GameState.PLAYING) {
            // 將傾斜值轉換為水平速度
            val horizontalSpeed = tiltX * 0.4f // 負號讓傾斜方向更直觀
            player.setHorizontalVelocity(horizontalSpeed)
        }
    }
    
    private fun update() {
        if (gameState != GameState.PLAYING) return
        
        // 更新玩家
        player.update()
        
        // 更新平台
        platforms.forEach { it.update() }
        
        // 檢查碰撞
        checkCollisions()
        
        // 更新分數
        scoreManager.updateScore(player.position.y)
        
        // 更新攝像機
        updateCamera()
        
        // 生成新平台
        generateNewPlatforms()
        
        // 檢查遊戲結束
        if (checkGameOver()) {
            gameState = GameState.GAME_OVER
        }
    }
    
    private fun checkCollisions() {
        platforms.forEach { platform ->
            if (player.collisionBox.intersects(platform.collisionBox) && 
                player.velocity.y > 0) { // 只有向下墜落時才觸發
                platform.onPlayerCollision(player)
            }
        }
    }
    
    private fun updateCamera() {
        // 攝像機跟隨玩家向上移動
        if (player.position.y < cameraOffset + CAMERA_FOLLOW_THRESHOLD) {
            cameraOffset = player.position.y - CAMERA_FOLLOW_THRESHOLD
        }
    }
    
    private fun generateNewPlatforms() {
        // 移除太低的平台
        platforms.removeAll { it.position.y > cameraOffset + SCREEN_HEIGHT + 5 }
        
        // 在頂部生成新平台
        val highestPlatform = platforms.minByOrNull { it.position.y }
        highestPlatform?.let { highest ->
            if (highest.position.y > cameraOffset - 10) {
                val newY = highest.position.y - Random.nextFloat() * 4 - 3
                val newX = Random.nextFloat() * (SCREEN_WIDTH - 4)
                val strategy = when (Random.nextInt(100)) {
                    in 0..79 -> NormalPlatformStrategy()
                    in 80..94 -> BouncyPlatformStrategy()
                    else -> MovingPlatformStrategy()
                }
                platforms.add(factory.createPlatform(Vector2D(newX, newY), strategy))
            }
        }
    }
    
    private fun checkGameOver(): Boolean {
        return player.position.y > cameraOffset + SCREEN_HEIGHT + 5
    }
    
    private fun render() {
        when (gameState) {
            GameState.HOME -> renderer?.renderHomeScreen()
            GameState.PLAYING -> {
                // 計算相對於攝像機的位置來渲染
                val adjustedPlayer = Player(Vector2D(player.position.x, player.position.y - cameraOffset))
                val adjustedPlatforms = platforms.map { platform ->
                    Platform(Vector2D(platform.position.x, platform.position.y - cameraOffset), NormalPlatformStrategy())
                }.filter { it.position.y >= -2 && it.position.y <= SCREEN_HEIGHT + 2 }
                
                renderer?.renderGame(adjustedPlayer, adjustedPlatforms)
            }
            GameState.PAUSED -> {} // 保持當前畫面
            GameState.GAME_OVER -> renderer?.renderGameOverScreen(scoreManager.getCurrentScore(), scoreManager.getHighScore())
        }
    }
    
    fun handleLongPress() {
        when (gameState) {
            GameState.HOME -> startGame()
            GameState.PLAYING -> pauseGame()
            GameState.PAUSED -> resumeGame()
            GameState.GAME_OVER -> restartGame()
        }
    }
    
    override fun onScoreChanged(newScore: Int, highScore: Int) {
        Log.d("Game", "Score: $newScore, High Score: $highScore")
    }
    
    fun cleanup() {
        gameLoop?.cancel()
        coroutineScope.cancel()
        scoreManager.removeObserver(this)
    }
}

// ========================= 服務控制器 =========================
class JumpGameService : GlyphMatrixService("Doodle-Jump-Game") {
    private companion object {
        private const val TAG = "DoodleJumpService"
        private const val MAX_TILT = 6f // 最大傾斜值，用於控制敏感度
    }

    private lateinit var game: Game
    private var tiltX = 0f

    /**
     * 服務創建時的初始化
     * 註冊加速度感應器並初始化遊戲系統
     */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Doodle Jump Game Service Created")
        try {
            registerSpecificSensor(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME)
            game = Game()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
        }
    }

    /**
     * Glyph Matrix 服務連接成功時調用
     * 設置渲染器並開始遊戲循環
     */
    override fun performOnServiceConnected(
        context: Context,
        glyphMatrixManager: GlyphMatrixManager
    ) {
        try {
            Log.d(TAG, "Service connected, initializing game renderer")
            
            // 設置渲染器
            val renderer = GameRenderer(glyphMatrixManager)
            game.setRenderer(renderer)
            Log.d(TAG, "Game initialized and ready to play - home screen rendered")
        } catch (e: Exception) {
            Log.e(TAG, "Error in performOnServiceConnected", e)
        }
    }

    /**
     * Glyph Matrix 服務斷開連接時調用
     * 清理資源並停止遊戲
     */
    override fun performOnServiceDisconnected(context: Context) {
        try {
            Log.d(TAG, "Service disconnected, cleaning up game")
            unregisterSpecificSensor(Sensor.TYPE_ACCELEROMETER)
            game.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Error in performOnServiceDisconnected", e)
        }
    }

    /**
     * 處理長按事件 - 使用命令模式處理狀態轉換
     * 根據當前遊戲狀態執行不同的操作
     */
    override fun onTouchPointLongPress() {
        try {
            Log.d(TAG, "Long press detected - current state: ${game.gameState}")
            game.handleLongPress()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onTouchPointLongPress", e)
        }
    }

    /**
     * 處理感應器數據變化
     * 使用加速度感應器的 X 軸數據控制玩家左右移動
     */
    override fun onSensorChanged(event: SensorEvent?) {
        try {
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                // 獲取 X 軸傾斜值並限制敏感度
                val rawTiltX = event.values[0]
                tiltX = max(-MAX_TILT, min(MAX_TILT, rawTiltX))
                
                // 更新玩家移動
                game.updatePlayerMovement(tiltX)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onSensorChanged", e)
        }
    }
}
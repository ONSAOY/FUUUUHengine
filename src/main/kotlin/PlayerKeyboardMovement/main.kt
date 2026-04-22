package PlayerKeyboardMovement

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.input.KeyCode
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.modules.ui2.UiModifier.*
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.addColorMesh
import de.fabmax.kool.scene.defaultOrbitCamera
import game.Move

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import questMaker.GridPos
import realGameScene.GameServer
import realGameScene.HudState
import kotlin.math.sqrt // квадратный корень числа(длина вектора и растояний)

// Импорты библиотеки desktop Keyboard bridge (JVM)
import java.awt.KeyEventDispatcher
// KeyEventDispatcher - перехватчик событий клавиатуры
// То есть, как объект, который видит, что мы нажимаем на клавиатуре

import java.awt.KeyboardFocusManager
// KeyboardFocusManager - менеджер фокусов окна
// Он нужен, чтобы добраться до системы ввода клавиатуры внутри активного окна

import java.awt.event.KeyEvent
// KeyEvent - событие нажатия клавиши
// В нем хранится: 1 - какая клавиша нажата, 2 - нажали или отпустили ее

// Mat formuli
import kotlin.math.abs
// abs(x) - модуль числа x
// abs(-3) = 3

import kotlin.math.atan2
// atan2(...) - функция для вычисления угла направления
// Она нужна для:
// 1 - понимания под каким углом должен смотреть игрок если идет в определенную сторону

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// Нужен для высчитывания направлений

// Он не двигает игрока
object DesktopKeyboardState{
    private val pressedKeys = mutableSetOf<Int>()
    // pressedKeys - набор кодов клавиш, которые сейчас зажаты
    // Set - набор уникальных чисел

    private val justPressedKeys = mutableSetOf<Int>()
    // justPressedKeys - набор клавиш, которые нажали вот, вот только что
    // Удобно для действий вроде:
    // любых одиночных разовых действий
    // Если не сделать этого, то клавиша взаимодействия E при удержании будет срабатывать каждый кадр

    private var isInstalled = false
    // isInstalled - флаг установки перехватчика клавиатуры
    // Нужен для: того чтобы команды не накладывались на, друг дуга

    fun install(){
        // install - установка слушателя клавиатуры
        // Делать это будем 1 раз, в самом начале программы

        if (isInstalled) return

        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(
                object : KeyEventDispatcher{
                    // object : Type {...} - это создание анонимного объекта
                    // "Создать объект перехватчик клавиатуры"

                    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                        // dispatchKeyEvent(...) - метод, который будет вызываться при событии нажатия на клавишу
                        when(e.id){
                            // проверяем тип клавиши
                            KeyEvent.KEY_PRESSED -> {
                                // KEY_PRESSED - клавиша нажата
                                if (!pressedKeys.contains(e.keyCode)){
                                    // .contains() - не нажата ли она ещё
                                    justPressedKeys.add(e.keyCode)
                                    // keyCode - код клавиши клавиатуры
                                }

                                pressedKeys.add(e.keyCode)
                            }

                            KeyEvent.KEY_RELEASED -> {
                                // События когда клавишу отпустили
                                pressedKeys.remove(e.keyCode)
                                justPressedKeys.remove(e.keyCode)
                                // Если клавишу отпустили - удалить из набора клавиш
                            }
                        }

                        return false
                        // Значит не блокировать это событие пусть событие его видит
                    }
                }
            )
        isInstalled = true
        // Слушатель уже поставлен и слушает
    }
    fun isDown(keyCode: Int): Boolean{
        // Нажата ли сейчас конкретная клавиша
        return keyCode in pressedKeys
    }

    fun consumeJustPressed(keyCode: Int): Boolean{
        // Ловим клавишу 1 раз
        return if (keyCode in justPressedKeys){
            justPressedKeys.remove(keyCode)
            true
        }else{
            false
        }
    }
}

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST,
    DOOR
}

data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val wordX: Float,
    val wordZ: Float,
    val interactRadius: Float
)

data class ObstacleDef(
    val centerX: Float,
    val centerZ: Float,
    val halfSize: Float
)
// Половина размера объекта удобна для определения столкновения с объектом по осям

data class NpcMemory(
    val hasMet: Boolean,
    val timesTalked: Int,
    val receivedHerb: Boolean
)

data class PlayerState(
    val playerId: String,

    val worldX: Float,
    val worldZ: Float,

    val yawDeg: Float,

    val moveSpeed: Float,

    val questState: QuestState,
    val inventory: Map<String, Int>,
    val gold: Int,

    val alchemistMemory: NpcMemory,

    val chestLooted: Boolean,
    val doorOpened: Boolean,

    val currentFocusId: String?,

    val hintText: String,
    val pinnedQuestEnabled: Boolean,
    val pinnedTargetId: String?
)

fun herbCount(player: PlayerState): Int{
    return player.inventory["herb"] ?: 0
}

fun larp(current: Float, target: Float, t:Float): Float{
    // Линейная интерполяция - нужна для перемещения обьекта от 1 точки к другой
    return current + (target - current) * t
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
    // Расчет расстояния между двумя точками на плоскости XZ
    // Школьная формула расстояния:
    // sqrt((dx * dx) + (dz * dz))
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx * dx + dz * dz)
}

fun normilizeOrZero(x: Float, z: Float): Pair<Float, Float>{
    // функция определяет произвольный вектор движения
    // Нужно если игрок зажал W и D одновременно, то движение будет быстрее
    // Это ошибка, поэтому нормализуем вектор движения

    val len = sqrt(x*x + z*z)

    return if (len <= 0.0001f){
        // Если длина почти ноль -> Игрок не может двигаться
        0f to 0f
    }else{
        (z / len) to (z/len)
        // После этой операции длина векторамтанет равно примерно 1
    }
}

fun computeYawDegDirection(dirX: Float, dirZ: Float): Float{
    // Проверка под каким углом надо смотреть игроку
    val raw = Math.toDegrees(atan2(dirX.toDouble() , dirZ.toDouble())).toFloat()
    // atan2 - позволяет, зная направление получить угол куда смотрит игрок
    // Math.toDegrees(...) - это преобразование в градусы
    // atan - возвращает угол в радианах, а нам нужны градусы
    // Double преобразовывает тк atan2 ожидает именно double, и он более точный
    return if (raw < 0f) raw + 360f else raw
    // Зачем?
    // atan может вернуть угол в минусовом значении
    // Нам нужно держать градусы в диапазоне т 0 до 360
    // Так что, чтобы преобразовать в положительное
}

fun initialPlayerState(playerId: String): PlayerState {
    return if (playerId == "Sas"){
        PlayerState(
            "Sas",
            0f,
            0f,
            0f,
            3.2f,
            QuestState.START,
            emptyMap(),
            2,
            NpcMemory(
                true,
                2,
                false
            ),
            false,
            false,
            null,
            "Подойди к любой области на карте",
            true,
            "alchemist"
        )
    }else{
        PlayerState(
            "Oleg",
            0f,
            0f,
            0f,
            3.2f,
            QuestState.START,
            emptyMap(),
            2,
            NpcMemory(
                false,
                0,
                false
            ),
            false,
            false,
            null,
            "Подойди к любой области на карте",
            true,
            "alchemist"
        )
    }
}

fun computePinnedTargetId(player: PlayerState): String?{
    if (!player.pinnedQuestEnabled) return null
    
    val herbs = herbCount(player)
    
    return when(player.questState){
        QuestState.START -> "alchemist"
        
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "herb_source" else "alchemist"
        }

        QuestState.GOOD_END -> {
            if (!player.chestLooted) "reward_chest"
            else if(!player.doorOpened) "door"
            else null
        }

        QuestState.EVIL_END -> null
    }
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)

fun buildAchemistDialogue(player: PlayerState): DialogueView {
    // Теперь показываем активный диалог только если в фокусе игрока именно алхимик

    if (player.currentFocusId != "alchemist"){
        return DialogueView(
            npcName = "Алхимик",
            text = "Эй сюда смотри!",
            options = emptyList()
        )
    }

    val herbs = herbCount(player)
    val memory= player.alchemistMemory

    return when(player.questState){
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet){
                    "Новое лицо, я тебя не помню"
                }else{
                    ""
                }
            DialogueView(
                "Алхимик",
                "$greeting \n Если хочешь варить траву, собери ее для начала",
                listOf(
                    DialogueOption(
                        "accept_help",
                        "Я буду варить"
                    ),
                    DialogueOption(
                        "threat",
                        "Давай сюда товар, быстро"
                    )
                )
            )
        }

        QuestState.WAIT_HERB -> {
            if (herbs < 3) {
                DialogueView(
                    "Алхимик",
                    "Пока ты собрал только $herbs/4 травы, возвращайся с полным товаром",
                    emptyList()
                )
            } else {
                DialogueView(
                    "Алхимик",
                    "Отличный товар, давай сюда!",
                    listOf(
                        DialogueOption(
                            "give_herb",
                            "Отдать 4 травы"
                        )
                    )
                )
            }
        }
        QuestState.GOOD_END -> {
            val text =
                if (memory.receivedHerb){
                    "Спасибо, я теперь точно много зелий наварю, можешь забрать свою награду"
                }else{
                    "Ты завершил квест, но память не обновилась"
                }
            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }
        QuestState.EVIL_END -> {
            DialogueView(
                "Алхимик",
                "Я не хочу с тобой разговаривать",
                emptyList()
            )
        }
    }
}

sealed interface GameCommand {
    val playerId: String
}

data class CmdMoveAxis(
    override val playerId: String,
    val axisX: Float,
    val axisZ: Float,
    val deltaSec: Float
): GameCommand

data class CmdInteract(
    override val playerId: String
): GameCommand

data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand

data class CmdTogglePinnedQuest(
    override val playerId: String
): GameCommand

sealed interface GameEvent {
    val playerId: String
}

data class PlayerMoved(
    override val playerId: String,
    val newWorldX: Float,
    val newWorldZ: Float
): GameEvent

data class MovementBlocked(
    override val playerId: String,
    val blockedWorldX: Float,
    val blockedWorldZ: Float
): GameEvent

data class FocusChanged(
    override val playerId: String,
    val newFocusId: String?
): GameEvent

data class PinnedTargetChanged(
    override val playerId: String,
    val newTarhetId: String?
): GameEvent

data class InteractedWithChest(
    override val playerId: String,
    val chestId: String
): GameEvent

data class InteractedWithDoor(
    override val playerId: String,
    val doorId: String
): GameEvent

data class InteractWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameEvent

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent

class GameServer{
    private val staticObstacles = listOf(
        ObstacleDef(centerX = 0f, centerZ = 1f, halfSize = 0.45f),
        ObstacleDef(centerX = 1f, centerZ = 1f, halfSize = 0.45f),
        ObstacleDef(centerX = 1f, centerZ = 0f, halfSize = 0.45f)
    )
    
    private val doorObstacle = ObstacleDef(centerX = 0f, centerZ = -3f, halfSize = 0.45f)

    val worldObjects = listOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "reward_chest",
            WorldObjectType.CHEST,
            0f,
            3f,
            1.3f
        ),
        WorldObjectDef(
            "door",
            WorldObjectType.DOOR,
            0f,
            -3f,
            1.3f
        )
    )

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _commands.tryEmit(cmd)
    // tryEmit - эт быстрый способ отправить команду (без корутины)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Sas" to initialPlayerState("Sas")
        )
    )
    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()

    val interact: Boolean = false

    fun getPlayerData(playerId: String): PlayerState {
        return _players.value[playerId] ?: initialPlayerState(playerId)
    }

    private fun setPlayerData(playerId: String, newdata: PlayerState) {
        val map = _players.value.toMutableMap()
        map[playerId] = newdata
        _players.value = map.toMap()
    }

    fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState){
        val oldMap =_players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()
    }

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        // Сервер слушает и выполняет их

        scope.launch {
            commands.collect{ cmd ->
                progressCommand(cmd)
            }
        }
    }

    private fun isPointInsideObstacle(x: Float, z: Float, obstacle: ObstacleDef, playerRadius: Float): Boolean{
        // Отвечает на вопрос, если у игрока точка (x, y), то он задел препятствие или нет
        return abs(x - obstacle.centerX) <= (obstacle.halfSize + playerRadius) &&
                abs(x - obstacle.centerZ) <= (obstacle.halfSize + playerRadius)
        // Определяем насколько мы далеко от центра препятствия
        // obstacle.halfSize + playerRadius - допустимая граница касания
    }

    private fun isBlockedForPlayer(player: PlayerState, x: Float, z: Float): Boolean{
        val playerRadius = 0.22f
        // Толщина игрока

        for (obstacle in staticObstacles){
            if (isPointInsideObstacle(x, z, obstacle, playerRadius)) return true
        }

        if (!player.doorOpened && isPointInsideObstacle(x, z, doorObstacle, playerRadius)){
            return true
        }

        return false
    }

    private fun isObjectAvailableForPlayer(obj: WorldObjectDef, player: PlayerState): Boolean{
        // Проверка на то, доступен ли иной объект для взаимодействия

        return when(obj.type){
            WorldObjectType.ALCHEMIST -> true
            WorldObjectType.HERB_SOURCE -> true

            WorldObjectType.CHEST -> player.questState == QuestState.GOOD_END && !player.chestLooted
            WorldObjectType.DOOR -> true
        }
    }

    private fun isObjectInFrontOfPlayer(player: PlayerState, obj: WorldObjectDef): Boolean{
        // Проверка, находится ли сейчас обьект перед игроком

        val yawRad = Math.toRadians(player.yawDeg.toDouble())
        // yawRad - угол взгляда игрока в градусах
        // sin и cos работают в радианах, надо конвертировать для работы с ними
        val forwardX = sin(yawRad).toFloat()
        val forwardZ = (-cos(yawRad)).toFloat()
        // Можно представить стрелу из груди персонажа, она показывает, куда смотрит игрок
        // forwardX и forwardZ - координаты стрелки этой плоскости

        val toObjX = obj.wordX - player.worldX
        val toObjZ = obj.wordZ - player.worldZ
        // Это вектор от игрока  к обьекту, де находиться объект относительно игрока

        val dist = max(0.0001f, distance2d(player.worldX, player.worldZ, obj.wordX, obj.wordZ))
        // Расстояние до объекта

        val dirToObjX = toObjX / dist
        val dirToObjZ = toObjZ / dist

        val dot = forwardX * dirToObjX + forwardZ * dirToObjZ
        // dot - скалярное произведение
        // Эта цифра отвечает на вопрос: насколько объект впереди?

        return dot > 0.45f
        // Если dot достаточно большой, считаем что объект спереди
        // Если 0.9 - смотрим почти идеально в центр
        // Если 0.1 - будет слишком мягко срабатывать
    }
    private fun pickInteractTarget(player: PlayerState): WorldObjectDef? {
        // Выбираем объект для взаимодействия
        val candidates = worldObjects.filter { obj ->
            isObjectAvailableForPlayer(obj, player) &&
                    distance2d(player.worldX, player.worldZ, obj.wordX, obj.wordZ) <= obj.interactRadius &&
                    isObjectInFrontOfPlayer(player, obj)
        }

        return candidates.minByOrNull { obj ->
            distance2d(player.worldX, player.worldZ, obj.wordX, obj.wordZ)
        }
    }
    private suspend fun refreshDerivedState(playerId: String){
        // Пересчет вторичных свойств игрока
        // Это те, что игрок вводит напрямую, они выводятся из других данных
        // Например:
        // - focus object
        // - active quest target
        // - hint text

        val player = getPlayerData(playerId)
        val target = pickInteractTarget(player)
        val newFocusId = target?.id

        val newPinnedTargetId = computePinnedTargetId(player)

        val newHint =
            when(newFocusId){
                "alchemist" -> "E: поговорить с алхимиком"
                "herb_source" -> "E: собрать траву"
                "reward_chest" -> "E: открыть сундук"
                "door" -> "E: открыть дверь"
                else -> " WASD / стрелки - движение, E - взаимодействие"
            }
        val oldFocusId = player.currentFocusId
        val oldPinnedId = player.pinnedTargetId

        updatePlayer(playerId){ p ->
            p.copy(
                currentFocusId = newFocusId,
                pinnedTargetId = newPinnedTargetId,
                hintText = newHint
            )
            // copy - создает новую копию data class с измененными полями
        }

        if (oldFocusId != newFocusId){
            _events.emit(FocusChanged(playerId, newFocusId))
        }

        if (oldPinnedId != newPinnedTargetId){
            _events.emit(PinnedTargetChanged(playerId, newPinnedTargetId))
        }
    }

    private suspend fun progressCommand(cmd: GameCommand){
        when(cmd){
            is CmdMoveAxis -> {
                val player = getPlayerData(cmd.playerId)

                val (dirX, dirZ) = normilizeOrZero(cmd.axisX, cmd.axisZ)
                // возвращает пару значений нормализованных X и Z
                if (dirX == 0f && dirZ == 0f){
                    refreshDerivedState(cmd.playerId)
                    return
                }
                val newYaw = computeYawDegDirection(dirX, dirZ)

                val distance = player.moveSpeed * cmd.deltaSec

                val newX = player.worldX + dirX * distance
                val newZ = player.worldZ + dirZ * distance

                val canMoveX = !isBlockedForPlayer(player, newX, player.worldZ)
                val canMoveZ = !isBlockedForPlayer(player, player.worldX, newZ)

                var finalX = player.worldX
                var finalZ = player.worldZ

                if (canMoveX) finalX = newX
                if (canMoveZ) finalZ = newZ

                if (!canMoveX && !canMoveZ){
                    _events.emit(MovementBlocked(cmd.playerId, newX, newZ))
                }

                updatePlayer(cmd.playerId){ p ->
                    p.copy(
                        worldX = finalX,
                        worldZ = finalZ,
                        yawDeg = newYaw
                    )
                }
                _events.emit(PlayerMoved(cmd.playerId, finalX, finalZ))
                refreshDerivedState(cmd.playerId)
            }
            is CmdInteract -> {
                val player = getPlayerData(cmd.playerId)
                val target = pickInteractTarget(player)

                if (target == null){
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет обьекта"))
                    return
                }

                when(target.type){
                    WorldObjectType.ALCHEMIST -> {
                        val oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timesTalked = oldMemory.timesTalked + 1
                        )

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        _events.emit(InteractWithNpc(cmd.playerId, target.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        refreshDerivedState(cmd.playerId)
                    }
                    WorldObjectType.HERB_SOURCE -> {
                        if (player.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Тебе сейчас не зачем эта трава"))
                            return
                        }

                        val oldCount = herbCount(player)
                        val newCount = oldCount + 1
                        val newInventory = player.inventory + ("herb" to newCount)

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(inventory = newInventory)
                        }

                        _events.emit(InteractedWithHerbSource(cmd.playerId, target.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                    }
                    WorldObjectType.CHEST -> {
                        if (player.questState != QuestState.GOOD_END){
                            _events.emit(ServerMessage(cmd.playerId, "Сначала пройди квест с травой"))
                            return
                        }

                        if (player.chestLooted){
                            _events.emit(ServerMessage(cmd.playerId, "Сундук уже залутан"))
                        }

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(
                                gold = p.gold + 20,
                                chestLooted = true
                            )
                        }

                        _events.emit(InteractedWithChest(cmd.playerId, target.id))
                        _events.emit(ServerMessage(cmd.playerId, "Ты получил 20 золотых"))
                        refreshDerivedState(cmd.playerId)
                    }

                    WorldObjectType.DOOR -> {
                        if (player.questState != QuestState.GOOD_END){
                            _events.emit(ServerMessage(cmd.playerId, "Дверь закрыта"))
                            return
                        }

                        if (player.doorOpened){
                            _events.emit(ServerMessage(cmd.playerId, "Дверь уже открыта"))
                            return
                        }

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(doorOpened = true)
                        }

                        _events.emit(InteractedWithDoor(cmd.playerId, target.id))
                        _events.emit(ServerMessage(cmd.playerId, "Ты открыл дверь"))
                        refreshDerivedState(cmd.playerId)
                    }

                }
            }

            is CmdChooseDialogueOption -> {
                val player = getPlayerData(cmd.playerId)

                if (player.currentFocusId != "alchemist"){
                    _events.emit(ServerMessage(cmd.playerId, "Сначала дойтди до алхимика"))
                    return
                }

                when(cmd.optionId){
                    "accept_help" -> {
                        if (player.questState != QuestState.START){
                            _events.emit(ServerMessage(cmd.playerId, "Путь пока не доступен, начни диалог"))
                            return
                        }

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(questState = QuestState.WAIT_HERB)
                        }

                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик дал тебе задание с травой"))
                    }
                    "threat" -> {
                        if (player.questState != QuestState.START){
                            _events.emit(ServerMessage(cmd.playerId, "Сначала поговори"))
                            return
                        }

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(questState = QuestState.EVIL_END)
                        }
                    }
                    "give_herb" -> {
                        if (player.questState != QuestState.WAIT_HERB) {
                            return
                        }

                        val herbs = herbCount(player)

                        if (herbs < 4){
                            return
                        }

                        val newCount = herbs - 4
                        val newInventory = if (newCount <= 0) player.inventory - "herb" else player.inventory + ("herb" to newCount)

                        val newMemory = player.alchemistMemory.copy(
                            receivedHerb = true
                        )

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(
                                inventory = newInventory,
                                questState = QuestState.GOOD_END,
                                alchemistMemory = newMemory
                            )
                        }

                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.GOOD_END))
                    }
                    else -> {
                        _events.emit(ServerMessage(cmd.playerId, "Неизвестный вариант диалога"))
                    }
                }
            }

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) { _ -> initialPlayerState(cmd.playerId) }
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен до заводских"))
            }
            
            is CmdTogglePinnedQuest -> {
                updatePlayer(cmd.playerId) { p ->
                    p.copy(
                        pinnedQuestEnabled = !p.pinnedQuestEnabled
                    )
                }
                
                val after = getPlayerData(cmd.playerId)
                _events.emit(ServerMessage(cmd.playerId, "Pinned marker = ${after.pinnedQuestEnabled}"))
                refreshDerivedState(cmd.playerId)
            }
        }
    }
}

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Tyler")
    val activePlayerIdUi = mutableStateOf("Tyler")

    val playerSnapShot = mutableStateOf(initialPlayerState("Tyler"))

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun formatInventory(player: PlayerState): String{
    return if (player.inventory.isEmpty()){
        "Инвентарь: пуст"
    }else{
        "Инвентарь: " + player.inventory.entries.joinToString { "${it.key} x${it.value}" }
    }
}

fun currentObjective(player: PlayerState): String{
    val herbs = herbCount(player)

    return when(player.questState){
        QuestState.START -> "Подойди к алхимику"
        QuestState.WAIT_HERB -> {
            if (herbs < 3) " Собери 4 травы $herbs/4"
            else "У тебя достаточно травы вернись к Хайзнбургеру"
        }

        QuestState.GOOD_END -> "Квест выполнена на хорошую концовку"
        QuestState.EVIL_END -> "Квест выполнена на плохую концовку"
    }
}

fun formatMemory(memory: NpcMemory): String{
    return "hasMet=${memory.hasMet}, talks=${memory.timesTalked}, receiveHerb=${memory.timesTalked}"
}

fun eventToText(e: GameEvent): String {
    return when (e) {
        is PlayerMoved -> "PlayerMoved x=${"%.2f".format(e.newWorldX)}, z=${"%.2f".format(e.newWorldZ)}"
        is MovementBlocked -> "MovementBlocked: x=${"%.2f".format(e.blockedWorldX)}, z=${"%.2f".format(e.blockedWorldZ)}"
        is FocusChanged -> "FocusChanged ${e.newFocusId}"
        is PinnedTargetChanged -> "PinnedTargetChanged ${e.newTarhetId}"
        is InteractedWithChest -> "InteractedWithChest ${e.chestId}"
        is InteractedWithDoor -> "InteractedWithDoor ${e.doorId}"
        is InteractWithNpc -> "InteractWithNpc ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource ${e.sourceId}"
        is InventoryChanged -> "InventoryChanged ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged Встретился = ${e.memory.hasMet}"
        is ServerMessage -> "ServerMessage ${e.text}"
    }
}

fun main() = KoolApplication{
    val hud = HudState()
    val server = GameServer()
    addScene {
        defaultOrbitCamera()

        for (x in -5..5){
            for (z in -4..4){
                addColorMesh {
                    generate { cube{colored()} }

                    shader = KslPbrShader{
                        color { vertexColor() }
                        metallic(0f)
                        roughness(0.35f)
                    }
                }.transform.translate(x.toFloat(), -1.2f, z.toFloat())
            }
        }

        val wallCells = listOf(
            GridPos(-1, 1),
            GridPos(0, 1),
            GridPos(1, 1),
            GridPos(1, 0)
        )


    }
}
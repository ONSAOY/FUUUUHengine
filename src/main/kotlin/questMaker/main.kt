package questMaker

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.modules.ui2.UiModifier.*
import de.fabmax.kool.math.deg
import kotlinx.coroutines.coroutineScope

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
import org.w3c.dom.Text

import kotlin.math.sqrt

// Flow - поток, в который иногда проходят разные значения
// событие "Человек сделал квест"
// новые состояния игрока и тд

// Поток данных, который выролняется паралельно другим потокам данных - корутина
// Внутри Flow код не начинает свою работу до тех пор, пока что-то не вызовет collect
// Каждый новый collect запустит поток заново

// StateFlow - набор состояний
// Любой stateFlow - существует сам по себе. Хранит в себе 1 текущее состояние которе меняется
// Когда появляется слушатель этого состояния - он получает его нынешнее значение и все последующее обновления состояния

// SharedFlow - рассыльщик событий (радио, громкоговоритель)
// Идеален для рассылки всем подпищекам

// collect - значит слушать поток и выполнять код внутри блока события
// Выполняет код когда пришло новое значение
// collect {...} - обработчик каждого послеидущего сообщения

// flow.collect{ value ->
//      println(value)
// }

// collect - важно запускать внутри корутины launch
// collect не завершается сам по себе

// === Слушатель событий === //
//coroutineScope.launch {
//    server.players.collect{ playerMAp ->
//        // Код в случае нового состояния игрока
//    }
//}

// Пример после подписки:
// 1.Кто то повлиял и обновил StateFlow игрока
// 2.collect увидит это тк он слушает все изменения StateFlow игрока
// 3.И выполнит код внутри блока collect

// emit - разослать событие
// tryEmit - быстрая отправка события без корутины

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class Facing{
    LEFT,
    RIGHT,
    FORWARD,
    BACK
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST,
    DOOR
}

data class GridPos(
    val x: Int,
    val z: Int
)

data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val cellX: Int,
    val cellZ: Int,
    val interactRadius: Float
)

data class NpcMemory(
    val hasMet: Boolean,        // Встретился ли игрок уже с NPC
    val timesTalked: Int,       // Сколько раз поговорил
    val receivedHerb: Boolean   // Отдали ли уже траву
)

data class PlayerState(
    val playerId: String,
    val gridX: Int,
    val gridZ: Int,
    val facing: Facing,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val gold: Int,
    val alchemistMemory: NpcMemory,
    val chestLooted: Boolean,
    val doorOpened: Boolean,
    val currentFocusId: String?,
    val hintText: String
)

fun facingToYawDeg(facing: Facing): Float{
    // Угол поворота игрока по оси Y
    return when(facing){
        Facing.FORWARD -> 0f
        Facing.RIGHT -> 90f
        Facing.BACK -> 180f
        Facing.LEFT -> 270F
    }
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

fun initialPlayerState(playerId: String): PlayerState {
    // Разделение на нескольких игроков

    return if (playerId == "Sas"){
        PlayerState(
            "Sas",
            0,
            0,
            Facing.FORWARD,
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
        )
    }else{
        PlayerState(
            "Tyler",
            0,
            0,
            Facing.FORWARD,
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
        )
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
            "Алхимик",
            "Эй сюда смотри!",
            emptyList()
        )
    }

    val herbs = herbCount(player)
    val memory= player.alchemistMemory

    return when(player.questState){
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet){
                    ""
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

sealed interface GameCommand{
    val playerId: String
}

// Команда перемещения игрока
data class CmdStepMove(
    override val playerId: String,
    val stepX: Int,
    val stepZ: Int
): GameCommand

// оманда взаимодействия игрока с обьектом
data class CmdInteract(
    override val playerId: String
): GameCommand

// Команда выбора варианта диалога
data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String,
): GameCommand
// События сервера к клиенту

sealed interface GameEvent{
    val playerId: String
}

data class PlayerMoved(
    override val playerId: String,
    val newGridX: Int,
    val newGridZ: Int
): GameEvent

data class MovementBlocked(
    override val playerId: String,
    val blockedX: Int,
    val blockedZ: Int
): GameEvent

data class InteractWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithChest(
    override val playerId: String,
    val chestId: String
): GameEvent

data class InteractedWithDoor(
    override val playerId: String,
    val doorId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameEvent

data class InteractedWithGoldSource(
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
    
    // Размеры карты
    private val minX = -5
    private val maxX = 5
    private val minZ = -4
    private val maxZ = 4
    
    // Статичные стены
    private val baseBlockedCells = setOf(
        GridPos(-1,1),
        GridPos(0,1),
        GridPos(1,1),
        GridPos(1,0),
        
    )
    
    // Дверь
    private val doorCell = GridPos(0, -3)
    
    // Список обьектов мира
    val worldObjects = listOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3,
            0,
            1.7f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3,
            0,
            1.7f
        ),
        WorldObjectDef(
            "reward_chest",
            WorldObjectType.CHEST,
            0,
            3,
            1.3f
        ),
        WorldObjectDef(
            "door",
            WorldObjectType.DOOR,
            0,
            -3,
            1.3f
        )
    )

    // Поток событий
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _commands.tryEmit(cmd)
    // tryEmit - эт быстрый способ отправить команду (без корутины)

    private val _players = MutableStateFlow(
        mapOf(
            "Tyler" to initialPlayerState("Tyler"),
            "Sas" to initialPlayerState("Sas")
        )
    )
    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()

    val interact: Boolean = false

    private fun isInteract(playerId: String, cmd: GameCommand){
        val player = getPlayerData(cmd.playerId)
        val obj = nearestObject(player)

        if (interact == true){
            updatePlayer(cmd.playerId) { p ->
                p.copy(gold = p.gold + 1)
            }
        }
    }



    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        // Сервер слушает и выполняет их

        scope.launch {
            commands.collect{ cmd ->
                progressCommand(cmd)
            }
        }
    }

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

    private fun isCellInsideMap(x: Int, z: Int): Boolean{
        return x in minX..maxX && z in minZ..maxZ
    }

    private fun isCellInsideMap(player: PlayerState, x: Int, z: Int): Boolean{
        if (GridPos(x, z) in baseBlockedCells) return true

        if (!player.doorOpened && x == doorCell.x && z == doorCell.z) return true

        return false
    }

    private fun isObjectAvailableForPlayer(obg: WorldObjectDef, player: PlayerState): Boolean{
        return when(obg.type){
            WorldObjectType.ALCHEMIST -> true
            WorldObjectType.HERB_SOURCE -> true
            WorldObjectType.DOOR -> true

            WorldObjectType.CHEST -> {
                player.questState == QuestState.GOOD_END && !player.chestLooted
            }
        }
    }

    // Проверка на то смотрит ли игрок на обьект с которым взаимодействует

    private fun isObjectInFrontPlayer(player: PlayerState, obj: WorldObjectDef): Boolean{
        // Facing.LEFT -> обьект должен быть слева (dx < 0)
        RIGHT DX > 0
        FORWARD DX < 0
        BACK DX > 0

        val dx = obj.cellX - player.gridX
    }

    // Поиск обьекта ближайшего, в чью зону, попадает игрок
    private fun nearestObject(player: PlayerState): WorldObjectDef?{
        val candidates = worldObjects.filter { obj ->
            distance2d(player.gridX.toFloat(), player.gridZ.toFloat(), obj.cellX.toFloat(), obj.cellZ.toFloat()) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->
            distance2d(player.gridX.toFloat(), player.gridZ.toFloat(), obj.cellX.toFloat(), obj.cellZ.toFloat())
        }
        // minByOrNull - минимальное из возможнх или null(взять ближайший обьект по расстоянию к игроку)
        // если список обьектов пуст вернуть null
    }

    private fun CmdChooseDialogueoption(playerId: String, cmd: GameCommand){
        val player = getPlayerData(cmd.playerId)
        when(cmd){
            is CmdInteract -> {
                val obj = nearestObject(player)
                if (obj?.type != WorldObjectType.ALCHEMIST){
                    _events.tryEmit(ServerMessage(cmd.playerId, "Ты отошел слишком далеко от Алхимика"))
                    return
                }
            }
            else -> ""
        }
    }

    private suspend fun refreshPlayerArea(playerId: String){
        val player = getPlayerData(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearest?.id

        if (oldAreaId == newAreaId){
            val newHint =
                when(newAreaId){
                    "alchemist" -> "Нажми для взаимодействия"
                    "herb_source" -> "Нажми для сбора травы"
                    else -> "Подойди к обьекту"
                }
            updatePlayer(playerId) {p -> p.copy(hintText = newHint)}
            return

        }
        if (oldAreaId != null){
            _events.emit(LeftArea(playerId, oldAreaId))
        }

        if (newAreaId != null){
            _events.emit(LeftArea(playerId, newAreaId))
        }

        val newHint =
            when(newAreaId){
                "alchemist" -> "Нажми для взаимодействия"
                "herb_source" -> "Нажми для сбора травы"
                else -> "Подойди к обьекту"
            }
        updatePlayer(playerId) {p ->
            p.copy(
                currentAreaId = newAreaId,
                hintText = newHint
            )
        }
    }
    private suspend fun progressCommand(cmd: GameCommand){
        when(cmd) {
            is CmdMovePlayer -> {
                updatePlayer(cmd.playerId){ p ->
                    p.copy(
                        posX = p.posX + cmd.dx,
                        posZ = p.posZ + cmd.dz
                    )
                }
                refreshPlayerArea(cmd.playerId)
            }
            is CmdInteract -> {
                val player = getPlayerData(cmd.playerId)
                val obj = nearestObject(player)

                if (obj == null){
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет обьекта"))
                    return
                }

                when(obj.type){
                    WorldObjectType.ALCHEMIST -> {
                        val oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timesTalked = oldMemory.timesTalked + 1
                        )

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        _events.emit(InteractWithNpc(cmd.playerId, obj.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
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

                        _events.emit(InteractedWithHerbSource(cmd.playerId, obj.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                    }
                    WorldObjectType.CHEST -> {
                        if (player.questState != QuestState.GOOD_END){
                            _events.emit(ServerMessage(cmd.playerId, "Сначала пройди квест с травой"))
                            return
                        }


                        val oldGoldCount = player.gold
                        val newGoldCount = oldGoldCount + 20


                        updatePlayer(cmd.playerId){ p ->
                            p.copy(gold = newGoldCount)
                        }

                        _events.emit(InteractedWithGoldSource(cmd.playerId, obj.id))
                    }
                    else -> ""

                }
            }

            is CmdChooseDialogueoption -> {
                val player = getPlayerData(cmd.playerId)

                if (player.currentAreaId != "alchemist"){
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
                            p.copy(questState = QuestState.BAD_END)
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

            is CmdSwitchActivePlayer -> {
                // Дома
            }

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) { _ -> initialPlayerState(cmd.playerId)}
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен до заводских"))
            }
        }
    }
}